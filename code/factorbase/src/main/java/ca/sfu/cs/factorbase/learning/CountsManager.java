package ca.sfu.cs.factorbase.learning;

/*zqian, April 1st, 2014 fixed the bug of too many connections by adding con4.close()*/


/*Jun 25,2013 @zqian
 * 
 * Trying to conquer the bottleneck of creating false tables (the join issue) by implementing our sort_merge algorithm.
 * Great stuff!
 * 
 * Here we have some different versions.
 * for version 3, naive implementing of sort merge with "load into" command in terms of efficiency issue of mysql insertion.
 * for version 4, concatenating the order by columns into one column when version 3 can not finish the order by .
 * 
 * for version 5, it's a kind of more complicated approach by pre-compressing all the attribute columns into one column, and then employing concatenating trick again on order by part.
 *   this version still has some bugs that need to be investagiated.
 *   
 * Preconditions: database_BN  has been created with lattice information and functor information.
 *  
 * */

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.logging.Logger;

import ca.sfu.cs.common.Configuration.Config;
import ca.sfu.cs.factorbase.data.FunctorNodesInfo;
import ca.sfu.cs.factorbase.lattice.LatticeGenerator;
import ca.sfu.cs.factorbase.lattice.RelationshipLattice;
import ca.sfu.cs.factorbase.util.MySQLScriptRunner;
import ca.sfu.cs.factorbase.util.QueryGenerator;
import ca.sfu.cs.factorbase.util.Sort_merge3;

import com.mysql.jdbc.Connection;
import com.mysql.jdbc.exceptions.jdbc4.MySQLSyntaxErrorException;

public class CountsManager {

    private static Connection con_BN;
    private static Connection con_CT;
    private static String databaseName_std;
    private static String databaseName_BN;
    private static String databaseName_CT;
    private static String databaseName_global_counts;
    private static String dbUsername;
    private static String dbPassword;
    private static String dbaddress;
    private static String linkCorrelation;
    private static long dbTemporaryTableSize;
    /*
     * cont is Continuous
     * ToDo: Refactor
     */
    private static String cont;
    private static Logger logger = Logger.getLogger(CountsManager.class.getName());

    static {
        setVarsFromConfig();
    }


    /**
     * @Overload
     * buildCT
     *
     * @throws SQLException if there are issues executing the SQL queries.
     */
    public static void buildCT() throws SQLException {
        try (Statement statement = con_BN.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + databaseName_CT + ";");
            statement.execute("CREATE SCHEMA " + databaseName_CT + " COLLATE utf8_general_ci;");
        }

        // Propagate metadata based on the FunctorSet.
        RelationshipLattice relationshipLattice = propagateFunctorSetInfo(con_BN);

        // Build the counts tables for the RChains.
        buildRChainCounts(con_CT, relationshipLattice);

        // building CT tables for Rchain
        CTGenerator(relationshipLattice);
    }


    /**
     * Use the FunctorSet to generate the necessary metadata for constructing CT tables.
     *
     * @param dbConnection - connection to the "_BN" database.
     * @return the relationship lattice created based on the FunctorSet.
     * @throws SQLException if there are issues executing the SQL queries.
     */
    private static RelationshipLattice propagateFunctorSetInfo(Connection dbConnection) throws SQLException {
        // Transfer metadata from the "_setup" database to the "_BN" database based on the FunctorSet.
        MySQLScriptRunner.callSP(
            dbConnection,
            "cascadeFS"
        );

        // Generate the relationship lattice based on the FunctorSet.
        RelationshipLattice relationshipLattice = LatticeGenerator.generate(
            dbConnection,
            databaseName_std
        );

        // TODO: Add support for Continuous = 1.
        if (cont.equals("1")) {
            throw new UnsupportedOperationException("Not Implemented Yet!");
        } else {
            MySQLScriptRunner.callSP(
                dbConnection,
                "populateMQ"
            );
        }

        MySQLScriptRunner.callSP(
            dbConnection,
            "populateMQRChain"
        );

        return relationshipLattice;
    }


    /*** this part we do need O.s. May 16, 2018 ***/
    /**
     *  Building the _CT tables for length >=2
     *  For each length
     *  1. find rchain, find list of members of rc-hain. Suppose first member is rnid1.
     *  2. initialize current_ct = rchain_counts after summing out the relational attributes of rnid1.
     *  3. Current list = all members of rchain minus rndi1. find ct(table) for current list = . Select rows where all members of current list are true. Add 1nodes of rnid1.
     *  4. Compute false table using the results of 2 and 3 (basically 2 - 3).
     *  5. Union false table with current_ct to get new ct where all members of current list are true.
     *  6. Repeat with current list as initial list until list is empty.
     *  Example:
     *  1. Rchain = R3,R2,R1. first rnid1 = R3.
     *  2. Find `R3,R2,R1_counts`. Sum out fields from R3 to get `R2,R1-R3_flat1`.
     *  3. Current list = R2,R1. Find `R2,R1_ct` where R1 = T, R2 = T. Add 1nodes of R3 (multiplying) to get `R2,R1-R3_star`.
     *  4. Compute `R2,R1-R3_false` = `R2,R1-R3_star - `R2,R1-R3_flat1` union (as before)
     *  5. Compute `R2,R1-R3_ct` = `R2,R1-R3_false` cross product `R3_join` union `R3,R2,R1_counts`.
     *  6. Current list = R1. Current rnid = R2. Current ct_table = `R2,R1-R3_ct`.
     *
     *  BuildCT_Rnodes_flat(len);
     *
     *  BuildCT_Rnodes_star(len);
     *
     *  BuildCT_Rnodes_CT(len);
     *
     * @param relationshipLattice - the relationship lattice used to determine which contingency tables to generate.
     * @throws SQLException if there are issues executing the SQL queries.
     */
    private static void CTGenerator(RelationshipLattice relationshipLattice) throws SQLException {
        int latticeHeight = relationshipLattice.getHeight();

        long l = System.currentTimeMillis(); //@zqian : CT table generating time
           // handling Pvars, generating pvars_counts       
        BuildCT_Pvars();
        
        // preparing the _join part for _CT tables
        Map<String, String> joinTableQueries = createJoinTableQueries();

        if (linkCorrelation.equals("1") && relationshipLattice.getHeight() != 0) {
            // handling Rnodes with Lattice Moebius Transform
            // Retrieve the first level of the lattice.
            List<FunctorNodesInfo> rchainInfos = relationshipLattice.getRChainsInfo(1);

            // Building the _flat tables.
            BuildCT_Rnodes_flat(rchainInfos);

            // Building the _star tables.
            BuildCT_Rnodes_star(rchainInfos);

            // Building the _false tables first and then the _CT tables.
            BuildCT_Rnodes_CT(rchainInfos, joinTableQueries);
            
            //building the _CT tables. Going up the Rchain lattice
            for(int len = 2; len <= latticeHeight; len++)
            { 
                rchainInfos = relationshipLattice.getRChainsInfo(len);
                logger.fine("now we're here for Rchain!");
                logger.fine("Building Time(ms) for Rchain >=2 \n");
                BuildCT_RChain_flat(rchainInfos, len, joinTableQueries);
                logger.fine(" Rchain! are done");
            }
        }

        long l2 = System.currentTimeMillis();  //@zqian
        logger.fine("Building Time(ms) for ALL CT tables:  "+(l2-l)+" ms.\n");
    }


    /**
     * Generate the global counts tables.
     */
    public static void buildRChainsGlobalCounts() throws SQLException {
        try(
            Connection conGlobalCounts = connectDB(databaseName_global_counts)
        ) {

            // Propagate metadata based on the FunctorSet.
            RelationshipLattice relationshipLattice = propagateFunctorSetInfo(con_BN);

            // Generate the global counts in the "_global_counts" database.
            buildRChainCounts(conGlobalCounts, relationshipLattice);
        }
    }


    /**
     * Build the "_counts" tables for the RChains in the given relationship lattice.
     *
     * @param dbConnection - connection to the database to create the "_counts" tables in.
     * @param relationshipLattice - the relationship lattice containing the RChains to build the "_counts" tables for.
     * @throws SQLException if there are issues executing the SQL queries.
     */
    private static void buildRChainCounts(
        Connection dbConnection,
        RelationshipLattice relationshipLattice
    ) throws SQLException {
        int latticeHeight = relationshipLattice.getHeight();

        // Building the <RChain>_counts tables.
        if(linkCorrelation.equals("1")) {
            // Generate the counts tables.
            for(int len = 1; len <= latticeHeight; len++){
                generateCountsTables(
                    dbConnection,
                    relationshipLattice.getRChainsInfo(len),
                    false
                );
            }
        } else {
            // Generate the counts tables and copy their values to the CT tables.
            for(int len = 1; len <= latticeHeight; len++) {
                generateCountsTables(
                    dbConnection,
                    relationshipLattice.getRChainsInfo(len),
                    true
                );
            }
        }
    }


    /**
     * setVarsFromConfig
     * ToDo : Remove Duplicate definitions across java files
     */
    private static void setVarsFromConfig() {
        Config conf = new Config();
        databaseName_std = conf.getProperty("dbname");
        databaseName_BN = databaseName_std + "_BN";
        databaseName_global_counts = databaseName_std + "_global_counts";
        databaseName_CT = databaseName_std + "_CT";
        dbUsername = conf.getProperty("dbusername");
        dbPassword = conf.getProperty("dbpassword");
        dbaddress = conf.getProperty("dbaddress");
        dbTemporaryTableSize = Math.round(1024 * 1024 * 1024 * Double.valueOf(conf.getProperty("dbtemporarytablesize")));
        linkCorrelation = conf.getProperty("LinkCorrelations");
        cont = conf.getProperty("Continuous");
    }

    /**
     * Connect to database via MySQL JDBC driver
     */
    private static Connection connectDB(String databaseName) throws SQLException {
        String CONN_STR = "jdbc:" + dbaddress + "/" + databaseName;
        try {
            java.lang.Class.forName("com.mysql.jdbc.Driver");
        } catch (Exception ex) {
            logger.severe("Unable to load MySQL JDBC driver");
        }
        return (Connection) DriverManager.getConnection(CONN_STR, dbUsername, dbPassword);
    }


    /**
     * Building the _CT tables. Going up the Rchain lattice ( When rchain.length >=2)
     * @param rchainInfos - FunctorNodesInfos for the RChains to build the "_CT" tables for.
     * @param len - length of the RChains to consider.
     * @param joinTableQueries - {@code Map} to retrieve the associated query to create a derived JOIN table.
     * @throws SQLException if there are issues executing the SQL queries.
     */
    private static void BuildCT_RChain_flat(
        List<FunctorNodesInfo> rchainInfos,
        int len,
        Map<String, String> joinTableQueries
    ) throws SQLException {
        logger.fine("\n ****************** \n" +
                "Building the _CT tables for Length = "+len +"\n" );
        int fc=0;
        for (FunctorNodesInfo rchainInfo : rchainInfos)
        {
            // Get the short and full form rnids for further use.
            String rchain = rchainInfo.getID();
            logger.fine("\n RChain : " + rchain);
            String shortRchain = rchainInfo.getShortID();
            logger.fine(" Short RChain : " + shortRchain);
            // Oct 16 2013
            // initialize the cur_CT_Table, at very beginning we will use _counts table to create the _flat table
            String cur_CT_Table = shortRchain + "_counts";
            logger.fine(" cur_CT_Table : " + cur_CT_Table);
            // counts represents the ct tables where all relationships in Rchain are true

            //  create new statement
            Statement st1 = con_BN.createStatement();
            ResultSet rs1 = st1.executeQuery(
                "SELECT DISTINCT parent, removed, short_rnid " +
                "FROM lattice_rel " +
                "JOIN lattice_mapping " +
                "ON lattice_rel.removed = lattice_mapping.orig_rnid " +
                "WHERE child = '" + rchain + "' " +
                "ORDER BY removed ASC;"
            ); // members of rchain

            while(rs1.next())
            {       
                long l2 = System.currentTimeMillis(); 
                String parent = rs1.getString("parent");
                logger.fine("\n parent : " + parent);
                String removed = rs1.getString("removed");
                logger.fine("\n removed : " + removed);  
                String removedShort = rs1.getString("short_rnid");
                logger.fine("\n removed short : " + removedShort);
                String BaseName = shortRchain + "_" + removedShort;
                logger.fine(" BaseName : " + BaseName );

                Statement st2 = con_BN.createStatement();
                Statement st3 = con_CT.createStatement();

                //  create select query string  


                ResultSet rs2 = st2.executeQuery("SELECT DISTINCT Entries FROM MetaQueries WHERE Lattice_Point = '" + rchain + "' and '"+removed+"' = EntryType and ClauseType = 'SELECT' and TableType = 'Star';");
                List<String> columns = extractEntries(rs2, "Entries");
                String selectString = makeDelimitedString(columns, ", ");
                logger.fine("Select String : " + selectString);
                rs2.close();
                //  create mult query string
                ResultSet rs3 = st2.executeQuery("SELECT DISTINCT Entries FROM MetaQueries WHERE Lattice_Point = '" + rchain + "' and '"+removed+"' = EntryType and ClauseType = 'FROM' and TableType = 'Star';");
                columns = extractEntries(rs3, "Entries");
                String MultString = makeStarSepQuery(columns);
                logger.fine("Mult String : " + MultString+ " as `MULT`");
                rs3.close();
                //  create from query string
                String fromString = makeDelimitedString(columns, ", ");
                logger.fine("From String : " + fromString);          
                //  create where query string
                ResultSet rs5 = st2.executeQuery("SELECT DISTINCT Entries FROM MetaQueries WHERE Lattice_Point = '" + rchain + "' and '"+removed+"' = EntryType and ClauseType = 'WHERE' and TableType = 'Star';");
                columns = extractEntries(rs5, "Entries");
                String whereString = makeDelimitedString(columns, " AND ");
               logger.fine("Where String : " + whereString);
                rs5.close();
                //  create the final query
                String queryString ="";
                if (!selectString.isEmpty() && !whereString.isEmpty()) {
                    queryString = "Select " +  MultString+ " as `MULT` ,"+selectString + " from " + fromString  + " where " + whereString;
                } else if (!selectString.isEmpty()) {
                    queryString = "Select " +  MultString+ " as `MULT` ,"+selectString + " from " + fromString;
                } else if (!whereString.isEmpty()) {
                    queryString =
                        "SELECT " + MultString + " AS `MULT` " +
                        "FROM " + fromString  + " " +
                        "WHERE " + whereString;
                } else {
                    queryString =
                        "SELECT " + MultString + " AS `MULT` " +
                        "FROM " + fromString;
                }
                logger.fine("Query String : " + queryString );   

                //make the rnid shorter 
                String rnid_or=removedShort;
            
                String cur_star_Table = removedShort + len + "_" + fc + "_star";
                String createStarString = "create table "+cur_star_Table +" ENGINE = MEMORY as "+queryString;

                logger.fine("\n create star String : " + createStarString );
                st3.execute(createStarString);      //create star table     

                long l3 = System.currentTimeMillis(); 
                logger.fine("Building Time(ms) for "+cur_star_Table+ " : "+(l3-l2)+" ms.\n");
                //staring to create the _flat table
                // Oct 16 2013
                // cur_CT_Table should be the one generated in the previous iteration
                // for the very first iteration, it's _counts table
                logger.fine("cur_CT_Table is : " + cur_CT_Table);

                String cur_flat_Table = removedShort + len + "_" + fc + "_flat";
                String queryStringflat = "SELECT SUM(`" + cur_CT_Table + "`.MULT) AS 'MULT' ";

                if (!selectString.isEmpty()) {
                    queryStringflat +=
                        ", " + selectString + " " +
                        "FROM `" + cur_CT_Table + "` " +
                        "GROUP BY " + selectString + ";";
                } else {
                    queryStringflat +=
                        "FROM `" + cur_CT_Table + "`;";
                }

                String createStringflat = "CREATE TABLE " + cur_flat_Table + " ENGINE = MEMORY AS " + queryStringflat;
                logger.fine("\n create flat String : " + createStringflat );         
                st3.execute(createStringflat);      //create flat table

                // Add covering index.
                addCoveringIndex(
                    con_CT,
                    databaseName_CT,
                    cur_flat_Table
                );

                long l4 = System.currentTimeMillis(); 
                logger.fine("Building Time(ms) for "+cur_flat_Table+ " : "+(l4-l3)+" ms.\n");
                /**********starting to create _flase table***using sort_merge*******************************/
                // starting to create _flase table : part1
                String cur_false_Table = removedShort + len + "_" + fc + "_false";

                // Computing the false table as the MULT difference between the matching rows of the star and flat tables.
                // This is a big join!
                Sort_merge3.sort_merge(
                    cur_star_Table,
                    cur_flat_Table,
                    cur_false_Table,
                    con_CT
                );

                long l5 = System.currentTimeMillis(); 
                logger.fine("Building Time(ms) for "+cur_false_Table+ " : "+(l5-l4)+" ms.\n");

                // staring to create the CT table
                ResultSet rs_45 = st2.executeQuery(
                    "SELECT column_name AS Entries " +
                    "FROM information_schema.columns " +
                    "WHERE table_schema = '" + databaseName_CT + "' " +
                    "AND table_name = '" + cur_CT_Table + "';"
                );
                columns = extractEntries(rs_45, "Entries");
                String CTJoinString = makeEscapedCommaSepQuery(columns);
                logger.fine("CT Join String : " + CTJoinString);

                //join false table with join table to add in rnid (= F) and 2nid (= n/a). then can union with CT table
                String QueryStringCT =
                    "SELECT " + CTJoinString + " " +
                    "FROM `" + cur_CT_Table + "` " +
                    "WHERE MULT > 0 " +

                    "UNION ALL " +

                    "SELECT " + CTJoinString + " " +
                    "FROM " +
                        "`" + cur_false_Table + "`, " +
                        "(" + joinTableQueries.get(rnid_or) + ") AS JOIN_TABLE " +
                    "WHERE MULT > 0;";

                String Next_CT_Table = "";

                if (rs1.next()) {
                    Next_CT_Table = BaseName + "_CT";
                } else {
                    Next_CT_Table = shortRchain + "_CT";
                }

                // Oct 16 2013
                // preparing the CT table for next iteration
                cur_CT_Table = Next_CT_Table;

                // Create CT table.
                st3.execute("CREATE TABLE `" + Next_CT_Table + "` ENGINE = MEMORY AS " + QueryStringCT);
                rs1.previous();

                fc++;   

                //  close statements
                st2.close();            
                st3.close();
                long l6 = System.currentTimeMillis(); 
                logger.fine("Building Time(ms) for "+cur_CT_Table+ " : "+(l6-l5)+" ms.\n");
            }
            st1.close();
            rs1.close();
        }
        logger.fine("\n Build CT_RChain_TABLES for length = "+len+" are DONE \n" );
    }


    /* building pvars_counts*/
    private static void BuildCT_Pvars() throws SQLException {
        long l = System.currentTimeMillis(); //@zqian : measure structure learning time
        Statement st = con_BN.createStatement();
        ResultSet rs = st.executeQuery(
            "SELECT " +
                "pvid " +
            "FROM " +
                "PVariables;"
        );

        while(rs.next()){
            //  get pvid for further use
            String pvid = rs.getString("pvid");
            logger.fine("pvid : " + pvid);
            //  create new statement
            Statement st2 = con_BN.createStatement();
            Statement st3 = con_CT.createStatement();
            //  create select query string
            ResultSet rs2 = st2.executeQuery(
                "SELECT " +
                    "Entries " +
                "FROM " +
                    "MetaQueries " +
                "WHERE " +
                    "Lattice_Point = '" + pvid + "' " +
                "AND " +
                    "ClauseType = 'SELECT' " +
                "AND " +
                    "TableType = 'Counts';"
            );

            List<String> columns = extractEntries(rs2, "Entries");
            String selectString = makeDelimitedString(columns, ", ");
            logger.fine("Select String : " + selectString);
            //  create from query string
            ResultSet rs3 = st2.executeQuery(
                "SELECT " +
                    "Entries " +
                "FROM " +
                    "MetaQueries " +
                "WHERE " +
                    "Lattice_Point = '" + pvid + "' " +
                "AND " +
                    "ClauseType = 'FROM' " +
                "AND " +
                    "TableType = 'Counts';"
            );
            columns = extractEntries(rs3, "Entries");
            String fromString = makeDelimitedString(columns, ", ");

            ResultSet rs_6 = st2.executeQuery(
                "SELECT " +
                    "Entries " +
                "FROM " +
                    "MetaQueries " +
                "WHERE " +
                    "Lattice_Point = '" + pvid + "' " +
                "AND " +
                    "ClauseType = 'GROUPBY' " +
                "AND " +
                    "TableType = 'Counts';"
            );
            columns = extractEntries(rs_6, "Entries");
            String GroupByString = makeDelimitedString(columns, ", ");

            /*
             *  Check for groundings on pvid
             *  If exist, add as where clause
             */
            logger.fine( "con_BN:SELECT id FROM Groundings WHERE pvid = '"+pvid+"';" );

            ResultSet rsGrounding = null;
            String whereString = "";

            try {
                rsGrounding = st2.executeQuery(
                    "SELECT " +
                        "Entries " +
                    "FROM " +
                        "MetaQueries " +
                    "WHERE " +
                        "Lattice_Point = '" + pvid + "' " +
                    "AND " +
                        "ClauseType = 'WHERE' " +
                    "AND " +
                        "TableType = 'Counts';"
                );
            } catch(MySQLSyntaxErrorException e) {
                logger.severe( "No WHERE clause for groundings" );
            }

            if (null != rsGrounding) {
                columns = extractEntries(rsGrounding, "Entries");
                whereString = makeDelimitedString(columns, " AND ");
            }

            logger.fine( "whereString:" + whereString );

            //  create the final query
            String queryString = "Select " + selectString + " from " +
                                 fromString + whereString;
                                 
//this seems unnecessarily complicated even to deal with continuos variables. OS August 22, 2017

            if (!cont.equals("1")) {
                if (!GroupByString.isEmpty()) {
                    queryString = queryString + " GROUP BY " + GroupByString;
                }
            }

            queryString += " HAVING MULT > 0";

            String countsTableName = pvid + "_counts";
            String createString = "CREATE TABLE " + countsTableName + " ENGINE = MEMORY AS " + queryString;
            logger.fine("Create String: " + createString);
            st3.execute(createString);

            //  close statements
            st2.close();
            st3.close();
        }

        rs.close();
        st.close();
        long l2 = System.currentTimeMillis(); //@zqian : measure structure learning time
        logger.fine("Building Time(ms) for Pvariables counts: "+(l2-l)+" ms.\n");
        logger.fine("\n Pvariables are DONE \n" );
    }


    /**
     * Create the "_counts" tables for the given RChains and copy the counts to the associated CT table if specified
     * to.
     *
     * @param dbConnection - connection to the database to create the "_counts" tables in.
     * @param rchainInfos - FunctorNodesInfos for the RChains to build the "_counts" tables for.
     * @param copyToCT - True if the values in the generated "_counts" table should be copied to the associated "_CT"
     *                   table; otherwise false.
     * @throws SQLException if there are issues executing the SQL queries.
     */
    private static void generateCountsTables(
        Connection dbConnection,
        List<FunctorNodesInfo> rchainInfos,
        boolean copyToCT
    ) throws SQLException {
        for (FunctorNodesInfo rchainInfo : rchainInfos) {
            // Get the short and full form rnids for further use.
            String rchain = rchainInfo.getID();
            logger.fine("\n RChain: " + rchain);
            String shortRchain = rchainInfo.getShortID();
            logger.fine(" Short RChain: " + shortRchain);

            String countsTableName = generateCountsTable(
                dbConnection,
                rchain,
                shortRchain
            );

            if (copyToCT) {
                try (Statement statement = con_CT.createStatement()) {
                    String createString_CT =
                        "CREATE TABLE `" + shortRchain + "_CT`" + " AS " +
                            "SELECT * " +
                            "FROM `" + countsTableName + "`";
                    logger.fine("CREATE String: " + createString_CT);
                    statement.execute(createString_CT);
                }
            }
        }

        logger.fine("\n RChain_counts are DONE \n");
    }


    /**
     * Generate the "_counts" table for the given RChain.
     *
     * @param dbConnection - connection to the database to create the "_counts" table in.
     * @param rchain - the full form name of the RChain.
     * @param shortRchain - the short form name of the RChain.
     * @return the name of the "_counts" table generated.
     * @throws SQLException if an error occurs when executing the queries.
     */
    private static String generateCountsTable(
        Connection dbConnection,
        String rchain,
        String shortRchain
    ) throws SQLException {
        // Create new statements.
        Statement st2 = con_BN.createStatement();
        Statement st3 = dbConnection.createStatement();

        // Create SELECT query string.
        ResultSet rs2 = st2.executeQuery(
            "SELECT DISTINCT Entries " +
            "FROM MetaQueries " +
            "WHERE Lattice_Point = '" + rchain + "' " +
            "AND ClauseType = 'SELECT' " +
            "AND TableType = 'Counts';"
        );

        List<String> selectAliases = extractEntries(rs2, "Entries");
        String selectString = makeDelimitedString(selectAliases, ", ");
        logger.fine("SELECT String: " + selectString);

        // Create FROM query string.
        ResultSet rs3 = st2.executeQuery(
            "SELECT DISTINCT Entries " +
            "FROM MetaQueries " +
            "WHERE Lattice_Point = '" + rchain + "' " +
            "AND ClauseType = 'FROM' " +
            "AND TableType = 'Counts';"
        );

        List<String> fromAliases = extractEntries(rs3, "Entries");
        String fromString = makeDelimitedString(fromAliases, ", ");
        logger.fine("FROM String: " + fromString);

        // Create WHERE query string.
        ResultSet rs4 = st2.executeQuery(
            "SELECT DISTINCT Entries " +
            "FROM MetaQueries " +
            "WHERE Lattice_Point = '" + rchain + "' " +
            "AND ClauseType = 'WHERE' " +
            "AND TableType = 'Counts';"
        );

        List<String> columns = extractEntries(rs4, "Entries");
        String whereString = makeDelimitedString(columns, " AND ");

        // Create the final query.
        String queryString =
            "SELECT " + selectString + " " +
            "FROM " + fromString + " " +
            "WHERE " + whereString;

        // Create GROUP BY query string.
        // This seems unnecessarily complicated - isn't there always a GROUP BY clause?
        // Okay, not with continuous data, but still.
        // Continuous probably requires a different approach.  OS August 22.
        if (!cont.equals("1")) {
            ResultSet rs_6 = st2.executeQuery(
                "SELECT DISTINCT Entries " +
                "FROM MetaQueries " +
                "WHERE Lattice_Point = '" + rchain + "' " +
                "AND ClauseType = 'GROUPBY' " +
                "AND TableType = 'Counts';"
            );

            columns = extractEntries(rs_6, "Entries");
            String GroupByString = makeDelimitedString(columns, ", ");

            if (!GroupByString.isEmpty()) {
                queryString = queryString + " GROUP BY "  + GroupByString;
            }
        }

        String countsTableName = shortRchain + "_counts";
        String createString = makeCountsTableQuery(countsTableName, selectAliases, fromAliases);
        st3.execute(createString);

        String insertString = "INSERT INTO `" + countsTableName + "` " + queryString;
        st3.execute("SET tmp_table_size = " + dbTemporaryTableSize + ";");
        st3.executeQuery("SET max_heap_table_size = " + dbTemporaryTableSize + ";");
        st3.execute(insertString);

        // Close statements.
        st2.close();
        st3.close();

        return countsTableName;
    }


    /**
     * building the _flat tables
     */
    private static void BuildCT_Rnodes_flat(List<FunctorNodesInfo> rchainInfos) throws SQLException {
        long l = System.currentTimeMillis(); //@zqian : measure structure learning time
        for (FunctorNodesInfo rchainInfo : rchainInfos) {
            // Get the short and full form rnids for further use.
            String rchain = rchainInfo.getID();
            logger.fine("\n RChain : " + rchain);
            String shortRchain = rchainInfo.getShortID();
            logger.fine(" Short RChain : " + shortRchain);

            //  create new statement
            Statement st2 = con_BN.createStatement();
            Statement st3 = con_CT.createStatement();


            //  create select query string
            ResultSet rs2 = st2.executeQuery("select distinct Entries from MetaQueries where Lattice_Point = '" + rchain + "' and TableType = 'Flat' and ClauseType = 'SELECT';");
            List<String> columns = extractEntries(rs2, "Entries");
            String selectString = makeDelimitedString(columns, ", ");

            //  create from query string
            ResultSet rs3 = st2.executeQuery("select distinct Entries from MetaQueries where Lattice_Point = '" + rchain + "' and TableType = 'Flat' and ClauseType = 'FROM';" );
            columns = extractEntries(rs3, "Entries");
            String fromString = makeDelimitedString(columns, ", ");

            //  create the final query
            String queryString = "Select " + selectString + " from " + fromString ;

            //  create group by query string
            if (!cont.equals("1")) {
                ResultSet rs_6 = st2.executeQuery("select distinct Entries from MetaQueries where Lattice_Point = '" + rchain + "' and TableType = 'Flat' and ClauseType = 'GROUPBY';");
                columns = extractEntries(rs_6, "Entries");
                String GroupByString = makeDelimitedString(columns, ", ");

                if (!GroupByString.isEmpty()) queryString = queryString + " group by"  + GroupByString;
                logger.fine("Query String : " + queryString );
            }

            String flatTableName = shortRchain + "_flat";
            String createString = "CREATE TABLE `" + flatTableName + "` ENGINE = MEMORY AS " + queryString;
            logger.fine("\n create String : " + createString );
            st3.execute(createString);

            // Add covering index.
            addCoveringIndex(
                con_CT,
                databaseName_CT,
                flatTableName
            );

            //  close statements
            st2.close();
            st3.close();
        }

        long l2 = System.currentTimeMillis(); //@zqian : measure structure learning time
        logger.fine("Building Time(ms) for Rnodes_flat: "+(l2-l)+" ms.\n");
        logger.fine("\n Rnodes_flat are DONE \n" );
    }

    /**
     * building the _star tables
     */
    private static void BuildCT_Rnodes_star(List<FunctorNodesInfo> rchainInfos) throws SQLException {
        long l = System.currentTimeMillis(); //@zqian : measure structure learning time
        for (FunctorNodesInfo rchainInfo : rchainInfos) {
            // Get the short and full form rnids for further use.
            String rchain = rchainInfo.getID();
            logger.fine("\n RChain : " + rchain);
            String shortRchain = rchainInfo.getShortID();
            logger.fine(" Short RChain : " + shortRchain);

            //  create new statement
            Statement st2 = con_BN.createStatement();
            Statement st3 = con_CT.createStatement();

            //  create select query string
            
            ResultSet rs2 = st2.executeQuery("select distinct Entries from MetaQueries where Lattice_Point = '" + rchain + "' and TableType = 'Star' and ClauseType = 'SELECT';");
            List<String> columns = extractEntries(rs2, "Entries");
            String selectString = makeDelimitedString(columns, ", ");


            //  create from MULT string
            ResultSet rs3 = st2.executeQuery("select distinct Entries from MetaQueries where Lattice_Point = '" + rchain + "' and TableType = 'Star' and ClauseType = 'FROM';");
            columns = extractEntries(rs3, "Entries");
            String MultString = makeStarSepQuery(columns);
            //makes the aggregate function to be used in the select clause //
            // looks like rs3 and rs4 contain the same data. Ugly! OS August 24, 2017

            //  create from query string
            String fromString = makeDelimitedString(columns, ", ");

            //  create the final query
            String queryString = "";
            if (!selectString.isEmpty()) {
                queryString = "Select " +  MultString+ " as `MULT` ,"+selectString + " from " + fromString ;
            } else {
                queryString = "Select " +  MultString+ " as `MULT`  from " + fromString ;
            }

            String starTableName = shortRchain + "_star";
            String createString = "CREATE TABLE `" + starTableName + "` ENGINE = MEMORY AS " + queryString;
            logger.fine("\n create String : " + createString );
            st3.execute(createString);

            //  close statements
            st2.close();
            st3.close();
        }

        long l2 = System.currentTimeMillis(); //@zqian : measure structure learning time
        logger.fine("Building Time(ms) for Rnodes_star: "+(l2-l)+" ms.\n");
        logger.fine("\n Rnodes_star are DONE \n" );
    }

    /**
     * Create the CT table for the given RChains.  This is done by building the "_false" tables first, then cross
     * joining it with the associated JOIN (derived) table, and then have the result UNIONed with the proper
     * "_counts" table.
     *
     * @param rchainInfos - FunctorNodesInfos for the RChains to build the "_CT" tables for.
     * @param joinTableQueries - {@code Map} to retrieve the associated query to create a derived JOIN table.
     * @throws SQLException if there are issues executing the SQL queries.
     */
    private static void BuildCT_Rnodes_CT(
        List<FunctorNodesInfo> rchainInfos,
        Map<String, String> joinTableQueries
    ) throws SQLException {
        long l = System.currentTimeMillis(); //@zqian : measure structure learning time
        for (FunctorNodesInfo rchainInfo : rchainInfos) {
            // Get the short and full form rnids for further use.
            String rchain = rchainInfo.getID();
            logger.fine("\n RChain : " + rchain);
            String shortRchain = rchainInfo.getShortID();
            logger.fine(" Short RChain : " + shortRchain);

            //  create new statement
            Statement st2 = con_BN.createStatement();
            Statement st3 = con_CT.createStatement();
            /**********starting to create _flase table***using sort_merge*******************************/
            String falseTableName = shortRchain + "_false";

            // Computing the false table as the MULT difference between the matching rows of the star and flat tables.
            // This is a big join!
            Sort_merge3.sort_merge(
                shortRchain + "_star",
                shortRchain + "_flat",
                falseTableName,
                con_CT
            );

            String countsTableName = shortRchain + "_counts";

            //building the _CT table        //expanding the columns // May 16
            // must specify the columns, or there's will a mistake in the table that mismatch the columns
            ResultSet rs5 = st3.executeQuery(
                "SELECT column_name AS Entries " +
                "FROM information_schema.columns " +
                "WHERE table_schema = '" + databaseName_CT + "' " +
                "AND table_name = '" + countsTableName + "';"
            );
            // reading the column names from information_schema.columns, and the output will remove the "`" automatically,
            // however some columns contain "()" and MySQL does not support "()" well, so we have to add the "`" back.
            List<String> columns = extractEntries(rs5, "Entries");
            String UnionColumnString = makeEscapedCommaSepQuery(columns);

            String ctTableName = shortRchain + "_CT";

            //join false table with join table to introduce rnid (=F) and 2nids (= n/a). Then union result with counts table.
            String createCTString =
                "CREATE TABLE `" + ctTableName + "` ENGINE = MEMORY AS " +
                    "SELECT " + UnionColumnString + " " +
                    "FROM `" + countsTableName + "` " +
                    "WHERE MULT > 0 " +

                    "UNION ALL " +

                    "SELECT " + UnionColumnString + " " +
                    "FROM " +
                        "`" + falseTableName + "`, " +
                        "(" + joinTableQueries.get(shortRchain) + ") AS JOIN_TABLE " +
                    "WHERE MULT > 0";
            logger.fine("\n create CT table String : " + createCTString );
            st3.execute(createCTString);

            //  close statements
            st2.close();
            st3.close();
        }

        long l2 = System.currentTimeMillis(); //@zqian : measure structure learning time
        logger.fine("Building Time(ms) for Rnodes_false and Rnodes_CT: "+(l2-l)+" ms.\n");
        logger.fine("\n Rnodes_false and Rnodes_CT  are DONE \n" );
    }


    /**
     * Create queries that can be used to create JOIN tables as derived tables in "FROM" clauses.
     * <p>
     * JOIN tables are used to help represent the case where a relationship is false and its attributes are undefined.
     * JOIN tables are cross joined with "_false" tables to help generate the "_CT" tables.
     * </p>
     * @return a {@code Map} object containing queries that can be used to create JOIN tables as derived tables.  The
     *         entries in the {@code Map} object have the form of &lt;short_rnid&gt;:&lt;joinTableQuery&gt;.
     * @throws SQLException if there are issues executing the SQL queries.
     */
    private static Map<String, String> createJoinTableQueries() throws SQLException {
        Map<String, String> joinTableQueries = new HashMap<String, String>();

        Statement st = con_BN.createStatement();
        ResultSet rs = st.executeQuery("select orig_rnid, short_rnid from LatticeRNodes ;");

        while(rs.next()){
        //  get rnid
            String short_rnid = rs.getString("short_rnid");
            logger.fine("\n short_rnid : " + short_rnid);
            String orig_rnid = rs.getString("orig_rnid");
            logger.fine("\n orig_rnid : " + orig_rnid);

            Statement st2 = con_BN.createStatement();

            //  create ColumnString
            ResultSet rs2 = st2.executeQuery(
                "SELECT DISTINCT Entries " +
                "FROM MetaQueries " +
                "WHERE Lattice_Point = '" + orig_rnid + "' " +
                "AND TableType = 'Join';"
            );

            List<String> columns = extractEntries(rs2, "Entries");
            String additionalColumns = makeDelimitedString(columns, ", ");
            StringBuilder joinTableQuerybuilder = new StringBuilder("SELECT \"F\" AS `" + orig_rnid + "`");
            if (!additionalColumns.isEmpty()) {
                joinTableQuerybuilder.append(", " + additionalColumns);
            }

            joinTableQueries.put(short_rnid, joinTableQuerybuilder.toString());
            st2.close();
        }

        rs.close();
        st.close();
        logger.fine("\n Rnodes_joins are DONE \n" );

        return joinTableQueries;
    }


    /**
     * Extract the values from the specified column of the given {@code ResultSet}.
     *
     * @param results - the ResultSet to extract the values from the specified column.
     * @param column - the column to extract the values from.
     * @return the list of values in the specified column of the given {@code ResultSet}.
     * @throws SQLException if an error occurs when trying to extract the column values.
     */
    private static List<String> extractEntries(ResultSet results, String column) throws SQLException {
        ArrayList<String> values = new ArrayList<String>();
        while (results.next()) {
            values.add(results.getString(column));
        }

        return values;
    }


    /**
     * Generate the multiplication string between the columns necessary to generate an _star table.
     *
     * @param columns - the columns to multiply together.
     * @return the multiplication string between the columns necessary to generate an _star table.
     */
    private static String makeStarSepQuery(List<String> columns) {
        String[] parts = new String[columns.size()];
        int index = 0;
        for (String column : columns) {
            parts[index] = column + ".MULT";
            index++;
        }

        return String.join(" * ", parts);
    }


    /**
     * Generate an escaped, comma delimited list of columns to generate an _CT table.
     *
     * @param columns - the columns to escape using backticks "`" and make a CSV with.
     * @return an escaped, comma delimited list of columns to generate an _CT table.
     */
    private static String makeEscapedCommaSepQuery(List<String> columns) {
        StringJoiner escapedCSV = new StringJoiner("`, `", "`", "`");

        for (String column : columns) {
            escapedCSV.add(column);
        }

        return escapedCSV.toString();
    }


    /**
     * Generate a delimited string of columns.
     *
     * @param columns - the columns to make a delimited string with.
     * @param delimiter - the delimiter to use for the delimited string.
     * @return a delimited string of columns.
     */
    private static String makeDelimitedString(List<String> columns, String delimiter) {
        String[] parts = new String[columns.size()];
        int index = 0;
        for (String column : columns) {
            parts[index] = column;
            index++;
        }

        return String.join(delimiter, parts);
    }


    /**
     * Create a query to generate an "_counts" table.
     *
     * @param countsTableName - the name of the "_counts" table that the query returned will generate when executed.
     * @param columnAliases - list of column aliases of the form "&lt;column&gt; AS &lt;alias&gt;".
     * @param tableAliases - list of table aliases of the form "&lt;table&gt; AS &lt;alias&gt;".
     * @return a query to generate an "_counts" table.
     * @throws SQLException if there are issues trying to retrieve the column data type information for the tables in
     *                      the given table aliases list.
     */
    private static String makeCountsTableQuery(
        String countsTableName,
        List<String> columnAliases,
        List<String> tableAliases
    ) throws SQLException {
        String[] tableNames = new String[tableAliases.size()];
        int index = 0;

        // for loop to extract the table names from the alias Strings.
        for (String tableAlias : tableAliases) {
            String fullyQualifiedTableName = tableAlias.split(" AS ")[0];
            String tableName = fullyQualifiedTableName.split("\\.")[1];
            tableNames[index] = tableName;
            index++;
        }

        // Retrieve the data type information for the columns of the tables extracted in the for loop above.
        ResultSet columnInfo = MySQLScriptRunner.callSP(con_BN, "getColumnsInfo", String.join(",", tableNames));

        Map<String, String> columnDataTypes = new HashMap<String, String>();
        columnDataTypes.put("\"T\"", "CHAR(1)");
        columnDataTypes.put("COUNT(*)", "BIGINT(21)");

        // while loop to store the data type information.
        while(columnInfo.next()) {
            columnDataTypes.put(
                columnInfo.getString("column_name"),
                columnInfo.getString("DataType")
            );
        }

        String[] columns = new String[columnAliases.size()];
        index = 0;

        for (String columnAlias : columnAliases) {
            String[] columnComponents = columnAlias.split(" AS ");
            String fullyQualifiedColumnName = columnComponents[0];
            String[] columnNameComponents = fullyQualifiedColumnName.split("\\.");
            String columnName = columnNameComponents[columnNameComponents.length - 1];
            String columnNameAlias = columnComponents[1].replace("\"", "");
            columns[index] = columnNameAlias + " " + columnDataTypes.get(columnName);
            index++;
        }

        return QueryGenerator.createTableQuery(countsTableName, columns);
    }


    /**
     * Add a covering index to the specified table.
     * <p>
     * Note: All columns will be part of the index except for the "MULT" column.  If there is only a "MULT" column in
     *       the table, no index will be created.
     * </p>
     *
     * @param dbConnection - connection to the database containing the table to create the covering index for.
     * @param databaseName - the name of the database that the specified table is located in.
     * @param tableName - the name of the table to add a covering index to.
     * @throws SQLException if an error occurs when executing the queries.
     */
    private static void addCoveringIndex(Connection dbConnection, String databaseName, String tableName) throws SQLException {
        String allColumnsQuery =
            "SELECT column_name " +
            "FROM information_schema.columns " +
            "WHERE table_schema = '" + databaseName + "' " +
            "AND table_name = '" + tableName + "';";

        try (
            Statement dbStatement = dbConnection.createStatement();
            ResultSet columnsResults = dbStatement.executeQuery(allColumnsQuery)
        ) {
            String columnsCSV = makeIndexQuery(columnsResults, "column_name", ", ");
            if (!columnsCSV.isEmpty()) {
                dbStatement.execute(
                    "ALTER TABLE `" + tableName + "` " +
                    "ADD INDEX CoveringIndex (" + columnsCSV + ");"
                );
            }
        }
    }


    /**
     * Making Index Query by adding "`" and appending ASC
     * @param rs
     * @param colName
     * @param del
     * @return
     * @throws SQLException
     */
    private static String makeIndexQuery(ResultSet rs, String colName, String del) throws SQLException {

        ArrayList<String> parts = new ArrayList<String>();
        int count=0;
        while(rs.next()&count<16){

                String temp =rs.getString(colName);
                if (temp.equals("MULT")) {
                    continue;
                }
                temp= "`"+temp+"`";
            parts.add(temp+ " ASC");
            count ++;
        }

        return String.join(del, parts);
    }


    /**
     * Connect to all the relevant databases.
     *
     * @throws SQLException if there are issues connecting to the databases.
     */
    public static void connectDB() throws SQLException {
        con_BN = connectDB(databaseName_BN);
        con_CT = connectDB(databaseName_CT);
    }


    /**
     * Disconnect from all the relevant databases.
     *
     * @throws SQLException if there are issues disconnecting from the databases.
     */
    public static void disconnectDB() throws SQLException {
        con_BN.close();
        con_CT.close();
    }
}