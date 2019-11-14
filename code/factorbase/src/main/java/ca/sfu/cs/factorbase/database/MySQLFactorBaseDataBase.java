package ca.sfu.cs.factorbase.database;

import java.io.IOException;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ca.sfu.cs.common.Configuration.Config;
import ca.sfu.cs.factorbase.data.ContingencyTable;
import ca.sfu.cs.factorbase.data.ContingencyTableGenerator;
import ca.sfu.cs.factorbase.data.DataExtractor;
import ca.sfu.cs.factorbase.data.DataExtractorGenerator;
import ca.sfu.cs.factorbase.data.FunctorNode;
import ca.sfu.cs.factorbase.data.FunctorNodesInfo;
import ca.sfu.cs.factorbase.data.MySQLDataExtractor;
import ca.sfu.cs.factorbase.exception.DataBaseException;
import ca.sfu.cs.factorbase.exception.DataExtractionException;
import ca.sfu.cs.factorbase.graph.Edge;
import ca.sfu.cs.factorbase.lattice.LatticeGenerator;
import ca.sfu.cs.factorbase.lattice.RelationshipLattice;
import ca.sfu.cs.factorbase.learning.CountsManager;
import ca.sfu.cs.factorbase.util.KeepTablesOnly;
import ca.sfu.cs.factorbase.util.LogWriter;
import ca.sfu.cs.factorbase.util.MySQLScriptRunner;
import ca.sfu.cs.factorbase.util.QueryGenerator;

import com.mysql.jdbc.Connection;

public class MySQLFactorBaseDataBase implements FactorBaseDataBase {

    private static final String CONNECTION_STRING = "jdbc:{0}/{1}";
    private String baseDatabaseName;
    private Connection dbConnection;
    private FactorBaseDataBaseInfo dbInfo;
    private Map<String, DataExtractor> dataExtractors;


    /**
     * Create a connection to the database server required by FactorBase to learn a Bayesian Network.
     *
     * @param dbInfo - database information related to FactorBase.
     * @param dbaddress - the address of the MySQL database server to connect to. e.g. mysql://127.0.0.1
     * @param dbname - the name of the database with the original data. e.g. unielwin
     * @param username - the username to use when accessing the database.
     * @param password - the password to use when accessing the database.
     * @throws SQLException if there is a problem connecting to the required database.
     */
    public MySQLFactorBaseDataBase(
        FactorBaseDataBaseInfo dbInfo,
        String dbaddress,
        String dbname,
        String username,
        String password
    ) throws DataBaseException {
        this.dbInfo = dbInfo;
        this.baseDatabaseName = dbname;
        String baseConnectionString = MessageFormat.format(CONNECTION_STRING, dbaddress, dbname);

        try {
            this.dbConnection = (Connection) DriverManager.getConnection(baseConnectionString, username, password);
        } catch (SQLException e) {
            throw new DataBaseException("Unable to connect to the provided database.", e);
        }
    }


    @Override
    public void setupDatabase() throws DataBaseException {
        try {
            // Using the base database connection.
            MySQLScriptRunner.runScript(
                this.dbConnection,
                Config.SCRIPTS_DIRECTORY + "initialize_databases.sql",
                this.baseDatabaseName
            );

            // Switch to start using the setup database.
            this.dbConnection.setCatalog(this.dbInfo.getSetupDatabaseName());
            MySQLScriptRunner.runScript(
                this.dbConnection,
                Config.SCRIPTS_DIRECTORY + "metadata.sql",
                this.baseDatabaseName
            );
            MySQLScriptRunner.runScript(
                this.dbConnection,
                Config.SCRIPTS_DIRECTORY + "metadata_storedprocedures.sql",
                this.baseDatabaseName,
                "//"
            );
            MySQLScriptRunner.callSP(this.dbConnection, "find_values");

            // Initialize tables for the global relationship lattice.
            MySQLScriptRunner.runScript(
                this.dbConnection,
                Config.SCRIPTS_DIRECTORY + "latticegenerator_initialize.sql",
                this.baseDatabaseName
            );

            // Switch to start using the BN database.
            this.dbConnection.setCatalog(this.dbInfo.getBNDatabaseName());

            MySQLScriptRunner.runScript(
                this.dbConnection,
                Config.SCRIPTS_DIRECTORY + "logging.sql",
                this.baseDatabaseName
            );

            MySQLScriptRunner.runScript(
                this.dbConnection,
                Config.SCRIPTS_DIRECTORY + "latticegenerator_populate.sql",
                this.baseDatabaseName,
                "//"
            );
            MySQLScriptRunner.runScript(
                this.dbConnection,
                Config.SCRIPTS_DIRECTORY + "transfer_cascade.sql",
                this.baseDatabaseName,
                "//"
            );
            MySQLScriptRunner.runScript(
                this.dbConnection,
                Config.SCRIPTS_DIRECTORY + "modelmanager_initialize.sql",
                this.baseDatabaseName
            );
            MySQLScriptRunner.runScript(
                this.dbConnection,
                Config.SCRIPTS_DIRECTORY + "metaqueries_populate.sql",
                this.baseDatabaseName,
                "//"
            );
            MySQLScriptRunner.runScript(
                this.dbConnection,
                Config.SCRIPTS_DIRECTORY + "metaqueries_RChain.sql",
                this.baseDatabaseName,
                "//"
            );
        } catch (SQLException | IOException e) {
            throw new DataBaseException("An error occurred when attempting to setup the database for FactorBase.", e);
        }
    }


    @Override
    public void cleanupDatabase() throws DataBaseException {
        try {
            KeepTablesOnly.Drop_tmpTables(
                this.dbConnection,
                this.dbInfo.getCTDatabaseName(),
                this.dbInfo.getBNDatabaseName()
            );
        } catch (SQLException e) {
            throw new DataBaseException("An error occurred when attempting to cleanup the database for FactorBase.", e);
        }
    }


    /**
     * Helper method to extract the edges from the given PreparedStatement.
     * @param statement - the PreparedStatement to extract the edge information from.
     * @return a List of the extracted edges.
     * @throws SQLException if an error occurs when attempting to retrieve the information.
     */
    private List<Edge> extractEdges(PreparedStatement statement) throws SQLException {
        ArrayList<Edge> edges = new ArrayList<Edge>();
        ResultSet results = statement.executeQuery();

        while (results.next()) {
            // Remove the backticks when creating edges so that they match the names in the CSV
            // file that gets generated.
            edges.add(
                new Edge(
                    results.getString("parent"),
                    results.getString("child")
                )
            );
        }

        return edges;
    }


    @Override
    public List<Edge> getForbiddenEdges(List<String> rnodeIDs) throws DataBaseException {
        String query = QueryGenerator.createSimpleInQuery(
            this.baseDatabaseName + "_BN.Path_Forbidden_Edges",
            "RChain",
            rnodeIDs
        );

        try (PreparedStatement st = this.dbConnection.prepareStatement(query)) {
            return extractEdges(st);
        } catch (SQLException e) {
            throw new DataBaseException("Failed to retrieve the forbidden edges.", e);
        }
    }


    @Override
    public List<Edge> getRequiredEdges(List<String> rnodeIDs) throws DataBaseException {
        String query = QueryGenerator.createSimpleInQuery(
            this.baseDatabaseName + "_BN.Path_Required_Edges",
            "RChain",
            rnodeIDs
        );

        try (PreparedStatement st = this.dbConnection.prepareStatement(query)) {
            return extractEdges(st);
        } catch (SQLException e) {
            throw new DataBaseException("Failed to retrieve the required edges.", e);
        }
    }


    @Override
    public DataExtractor getAndRemoveCTDataExtractor(String dataExtractorID) throws DataExtractionException {
        if (this.dataExtractors == null) {
            this.dataExtractors = this.generateDataExtractors();
        }

        return this.dataExtractors.remove(dataExtractorID);
    }


    /**
     * Generate all the CT table {@code DataExtractor}s of the FactorBase database.
     *
     * @return a Map containing key:value pairs of dataExtractorID:DataExtractor.
     * @throws DataExtractionException if an error occurs when retrieving the DataExtractors.
     */
    private Map<String, DataExtractor> generateDataExtractors() throws DataExtractionException {
        return DataExtractorGenerator.generateMySQLExtractors(
            this.dbConnection,
            this.dbInfo
        );
    }


    @Override
    public List<FunctorNodesInfo> getPVariablesFunctorNodeInfo() throws DataBaseException {
        String setupDatabaseName = this.dbInfo.getSetupDatabaseName();
        String query =
            "SELECT P.pvid, N.1nid AS Fid, A.Value " +
            "FROM " +
                setupDatabaseName + ".PVariables P, " +
                setupDatabaseName + ".1Nodes N, " +
                setupDatabaseName + ".Attribute_Value A " +
            "WHERE P.pvid = N.pvid " +
            "AND N.COLUMN_NAME = A.COLUMN_NAME " +
            "AND index_number = 0 " +
            "ORDER BY pvid, 1nid";

        List<FunctorNodesInfo> functorNodesInfos = new ArrayList<FunctorNodesInfo>();
        String previousPVarID = null;
        String previousFunctorNodeID = null;
        try (
            Statement statement = this.dbConnection.createStatement();
            ResultSet results = statement.executeQuery(query)
        ) {
            FunctorNodesInfo info = null;
            FunctorNode functorNode = null;
            while (results.next()) {
                String currentPVarID = results.getString("pvid");
                String currentFunctorNodeID = results.getString("Fid");
                if (!currentPVarID.equals(previousPVarID)) {
                    info = new FunctorNodesInfo(currentPVarID, this.dbInfo.isDiscrete(), false);
                    functorNodesInfos.add(info);
                    previousPVarID = currentPVarID;
                }

                if (!currentFunctorNodeID.equals(previousFunctorNodeID)) {
                    functorNode = new FunctorNode(currentFunctorNodeID);
                    info.addFunctorNode(functorNode);
                    previousFunctorNodeID = currentFunctorNodeID;
                }

                functorNode.addState(results.getString("Value"));
            }
        } catch (SQLException e) {
            throw new DataBaseException("Failed to retrieve the functor nodes information for the PVariables.", e);
        }

        return functorNodesInfos;
    }


    /**
     * Retrieve the functor node information for all the RNodes.
     *
     * @return Information for all the functor nodes of each RNode in the database.
     * @throws DataBaseException if an error occurs when attempting to retrieve the information.
     */
    private Map<String, FunctorNodesInfo> getRNodesFunctorNodeInfo() throws DataBaseException {
        String setupDatabaseName = this.dbInfo.getSetupDatabaseName();
        String query =
            "SELECT R.rnid, N.1nid as Fid, '1Node' AS Type, A.value " +
            "FROM " +
                setupDatabaseName + ".1Nodes AS N, " +
                setupDatabaseName + ".RNodes_pvars AS R, " +
                setupDatabaseName + ".Attribute_Value AS A " +
            "WHERE N.pvid = R.pvid AND N.COLUMN_NAME = A.COLUMN_NAME " +

            "UNION ALL " +

            "SELECT R.rnid, R.2nid as Fid, '2Node' AS Type, A.value " +
            "FROM " +
                setupDatabaseName + ".2Nodes AS N, " +
                setupDatabaseName + ".RNodes_2Nodes AS R, " +
                setupDatabaseName + ".Attribute_Value AS A " +
            "WHERE N.2nid = R.2nid AND N.COLUMN_NAME = A.COLUMN_NAME " +

            "ORDER BY rnid, Fid;";

        Map<String, FunctorNodesInfo> functorNodesInfos = new HashMap<String, FunctorNodesInfo>();
        String previousRNodeID = null;
        String previousFunctorNodeID = null;
        try (
            Statement statement = this.dbConnection.createStatement();
            ResultSet results = statement.executeQuery(query)
        ) {
            FunctorNodesInfo info = null;
            FunctorNode functorNode = null;
            while (results.next()) {
                String currentRNodeID = results.getString("rnid");
                String currentFunctorNodeID = results.getString("Fid");
                if (!currentRNodeID.equals(previousRNodeID)) {
                    info = new FunctorNodesInfo(currentRNodeID, this.dbInfo.isDiscrete(), false);

                    // The SELECT statement above doesn't retrieve the RNode as a functor node so we add it here.
                    FunctorNode fnode = new FunctorNode(currentRNodeID);
                    fnode.addState("T");
                    fnode.addState("F");
                    info.addFunctorNode(fnode);

                    functorNodesInfos.put(currentRNodeID, info);
                    previousRNodeID = currentRNodeID;
                }

                if (!currentFunctorNodeID.equals(previousFunctorNodeID)) {
                    functorNode = new FunctorNode(currentFunctorNodeID);

                    // The SELECT statement above doesn't retrieve the "N/A" value so we add it in here.
                    if (results.getString("Type").equals("2Node")) {
                        functorNode.addState("N/A");
                    }

                    info.addFunctorNode(functorNode);
                    previousFunctorNodeID = currentFunctorNodeID;
                }

                functorNode.addState(results.getString("Value"));
            }
        } catch (SQLException e) {
            throw new DataBaseException("Failed to retrieve the functor nodes information for the RNodes.", e);
        }

        return functorNodesInfos;
    }


    @Override
    public ContingencyTable getContingencyTable(
        FunctorNodesInfo functorInfos,
        String child,
        Set<String> parents,
        int totalNumberOfStates
    ) throws DataBaseException {
        try {
            Set<String> allFunctorNodesExceptChild = new HashSet<String>(parents);

            // If we're learning at an RChain point in the relationship lattice, add the RNodes of the RChain to the
            // FunctorSet.
            Set<String> family = new HashSet<String>(parents);
            family.add(child);
            if (functorInfos.isRChainID()) {
                String[] rnodes = functorInfos.getID().replace("),", ") ").split(" ");
                allFunctorNodesExceptChild = new HashSet<String>(Arrays.asList(rnodes));

                // Attempt to remove the child variable from the set so that we don't run into a duplicate issue when
                // inserting into the FunctorSet table.
                allFunctorNodesExceptChild.remove(child);

                // Combine RNodes of the RChain with the parents to get our final set that has all the FunctorNodes
                // except the child.
                allFunctorNodesExceptChild.addAll(parents);

                for (String rnode : rnodes) {
                    // Update the total number of combination of states possible only if the RNode is not already in
                    // the FunctorSet.
                    if (!rnode.equals(child) && !parents.contains(rnode)) {
                        totalNumberOfStates *= functorInfos.getNumberOfStates(rnode);
                    }
                }
            }
            this.dbConnection.setCatalog(this.dbInfo.getSetupDatabaseName());
            try (Statement statement = this.dbConnection.createStatement()) {
                // Initialize FunctorSet table.
                statement.executeUpdate(QueryGenerator.createTruncateQuery("FunctorSet"));
                statement.executeUpdate(
                    QueryGenerator.createSimpleExtendedInsertQuery(
                        "FunctorSet",
                        child,
                        allFunctorNodesExceptChild
                    )
                );
            }
            Set<String> allFunctorNodes = new HashSet<String>(allFunctorNodesExceptChild);
            allFunctorNodes.add(child);
            List<String> allFNs = new ArrayList<String>(allFunctorNodes);
            Collections.sort(allFNs);
            LogWriter.addLog(
                this.dbConnection,
                functorInfos.getID(),
                String.join(",", family),
                child
            );
            long start = System.currentTimeMillis();
            // Generate CT tables.
            CountsManager.buildCT(true);
            LogWriter.updateLog(this.dbConnection, "Total", System.currentTimeMillis() - start);
            String tableName = null;
            String shortID = functorInfos.getShortID();
            if (shortID != null) {
                tableName = shortID + "_CT";
            } else {
                tableName = functorInfos.getID() + "_counts";
            }

            PreparedStatement query = this.dbConnection.prepareStatement(
                "SELECT * " +
                "FROM " + dbInfo.getCTDatabaseName() + ".`" + tableName + "` " +
                "WHERE MULT > 0;"
            );

            DataExtractor dataextractor = new MySQLDataExtractor(query, dbInfo.getCountColumnName(), dbInfo.isDiscrete());
            ContingencyTableGenerator ctGenerator = new ContingencyTableGenerator(dataextractor);
            int childColumnIndex = ctGenerator.getColumnIndex(child);
            int[] parentColumnIndices = ctGenerator.getColumnIndices(parents);
            return ctGenerator.generateCT(childColumnIndex, parentColumnIndices, totalNumberOfStates);
        } catch (DataExtractionException | SQLException e) {
            throw new DataBaseException("Failed to generate the CT table.", e);
        }
    }


    @Override
    public RelationshipLattice getGlobalLattice() throws DataBaseException {
        Map<String, FunctorNodesInfo> functorNodesInfos = this.getRNodesFunctorNodeInfo();

        try {
            this.dbConnection.setCatalog(this.dbInfo.getSetupDatabaseName());
            return LatticeGenerator.generateGlobal(
                functorNodesInfos,
                this.dbConnection,
                "RNodes",
                "rnid"
            );
        } catch (SQLException e) {
            throw new DataBaseException("Failed to retrieve the relationship lattice for the database.", e);
        }
    }


    @Override
    public void insertLearnedEdges(
        String id,
        List<Edge> graphEdges,
        String destTableName,
        boolean removeForbiddenEdges
    ) throws DataBaseException {
        try {
            this.dbConnection.setCatalog(this.dbInfo.getBNDatabaseName());

            try(Statement statement = this.dbConnection.createStatement()) {
                for (Edge graphEdge : graphEdges) {
                    statement.execute(
                        "INSERT IGNORE INTO " + destTableName + " " +
                        "VALUES (" +
                            "'" + id + "', " +
                            "'" + graphEdge.getChild() + "', " +
                            "'" + graphEdge.getParent() + "'" +
                        ");"
                    );
                }

                // Delete the edges which are already forbidden in a lower level.
                // TODO: Figure out if this is actually necessary.
                if (removeForbiddenEdges) {
                    statement.execute(
                        "DELETE FROM " + destTableName + " " +
                        "WHERE Rchain = '" + id + "' " +
                        "AND (child, parent) IN (" +
                            "SELECT child, parent " +
                            "FROM Path_Forbidden_Edges " +
                            "WHERE Rchain = '" + id + "'" +
                        ");"
                    );
                }
            }
        } catch (SQLException e) {
            throw new DataBaseException("Failed to insert/remove edges from the specified table.", e);
        }
    }


    @Override
    public void propagateEdgeInformation(int height, boolean linkAnalysisOn) throws DataBaseException {
        // Import edge information to the database.
        try {
            this.dbConnection.setCatalog(this.dbInfo.getBNDatabaseName());
            try(Statement statement = this.dbConnection.createStatement()) {
                // Propagate all edges to the next level.
                statement.execute(
                    "INSERT IGNORE INTO InheritedEdges " +
                        "SELECT DISTINCT " +
                            "lattice_rel.child AS Rchain, " +
                            "Path_BayesNets.child AS child, " +
                            "Path_BayesNets.parent AS parent " +
                        "FROM " +
                            "Path_BayesNets, " +
                            this.dbInfo.getSetupDatabaseName() + ".lattice_rel, " +
                            this.dbInfo.getSetupDatabaseName() + ".lattice_set " +
                        "WHERE " +
                            "lattice_rel.parent = Path_BayesNets.Rchain " +
                        "AND " +
                            "Path_BayesNets.parent <> '' " +
                        "AND " +
                            "lattice_set.name = lattice_rel.parent " +
                        "AND " +
                            "lattice_set.length = " + height + " " +
                        "ORDER BY " +
                            "Rchain;"
                );

                if (!linkAnalysisOn) {
                    linkAnalysisOffSpecificEdgePropagation(statement, height);
                }

                // Make inherited edges as required edges, while avoiding conflict edges.
                // Design Three Required Edges: propagate edges from/to 1Nodes/2Nodes + SchemaEdges + RNodes to 1Nodes/2Nodes (same as Design Two).
                statement.execute(
                    "INSERT IGNORE INTO Path_Required_Edges " +
                        "SELECT DISTINCT " +
                            "Rchain, " +
                            "child, " +
                            "parent " +
                        "FROM " +
                            "InheritedEdges, " +
                            this.dbInfo.getSetupDatabaseName() + ".lattice_set " +
                        "WHERE " +
                            "Rchain = lattice_set.name " +
                        "AND " +
                            "lattice_set.length = " + (height + 1) + " " +
                        "AND (" +
                            "Rchain, " +
                            "parent, " +
                            "child" +
                        ") NOT IN (" +
                            "SELECT " +
                                "* " +
                            "FROM " +
                                "InheritedEdges" +
                        ") AND " +
                                "child " +
                        "NOT IN (" +
                            "SELECT " +
                                "rnid " +
                            "FROM " +
                                this.dbInfo.getSetupDatabaseName() + ".RNodes" +
                        ");"
                );

                // For path_complemtment edges, rchain should be at current level (len).
                statement.execute(
                    "INSERT IGNORE INTO Path_Complement_Edges " +
                        "SELECT DISTINCT " +
                            "BN_nodes1.Rchain AS Rchain, " +
                            "BN_nodes1.node AS child, " +
                            "BN_nodes2.node AS parent " +
                        "FROM " +
                            "Path_BN_nodes AS BN_nodes1, " +
                            "Path_BN_nodes AS BN_nodes2, " +
                            this.dbInfo.getSetupDatabaseName() + ".lattice_set " +
                        "WHERE " +
                            "lattice_set.name = BN_nodes1.Rchain " +
                        "AND " +
                            "lattice_set.length = " + height + " " +
                        "AND " +
                            "BN_nodes1.Rchain = BN_nodes2.Rchain " +
                        "AND NOT EXISTS (" +
                            "SELECT " +
                                "* " +
                            "FROM " +
                                "Path_BayesNets " +
                            "WHERE " +
                                "Path_BayesNets.Rchain = BN_nodes1.Rchain " +
                            "AND " +
                                "Path_BayesNets.child = BN_nodes1.node " +
                            "AND " +
                                "Path_BayesNets.parent = BN_nodes2.node" +
                        ");"
                );

                // For path forbidden edges, rchain should be at higher level (len+1), so its parent should be at current level (len).
                // Make absent edges as forbidden edges, and give higher priority of required edges in case of conflict edges.
                // Design Three Forbidden Edges: propagate edges from/to 1Nodes/2Nodes + 1Nodes/2Nodes to RNodes.
                statement.execute(
                    "INSERT IGNORE INTO Path_Forbidden_Edges " +
                        "SELECT DISTINCT " +
                            "lattice_rel.child AS Rchain, " +
                            "Path_Complement_Edges.child AS child, " +
                            "Path_Complement_Edges.parent AS parent " +
                        "FROM " +
                            "Path_Complement_Edges, " +
                            this.dbInfo.getSetupDatabaseName() + ".lattice_rel, " +
                            this.dbInfo.getSetupDatabaseName() + ".lattice_set " +
                        "WHERE " +
                            "lattice_set.name = lattice_rel.parent " +
                        "AND " +
                            "lattice_set.length = " + height + " " +
                        "AND " +
                            "lattice_rel.parent = Path_Complement_Edges.Rchain " +
                        "AND " +
                            "Path_Complement_Edges.parent <> '' " +
                        "AND (" +
                            "lattice_rel.child, " +
                            "Path_Complement_Edges.child, " +
                            "Path_Complement_Edges.parent" +
                        ") NOT IN (" +
                            "SELECT " +
                                "Rchain, " +
                                "child, " +
                                "parent " +
                            "FROM " +
                                "Path_Required_Edges" +
                        ") AND " +
                            "Path_Complement_Edges.parent " +
                        "NOT IN (" +
                            "SELECT " +
                                "rnid " +
                            "FROM " +
                                this.dbInfo.getSetupDatabaseName() + ".RNodes" +
                        ");"
                );
            }
        } catch (SQLException e) {
            throw new DataBaseException("Failed to propagate the edge information to the next level.", e);
        }
    }


    /**
     * Helper method for {@link #propagateEdgeInformation(int, boolean)} to make it more clear what queries are
     * specific for the LinkAnalysis off case.
     *
     * @param statement - statement object to execute the queries with.
     * @param height - the lattice level to propagate edges from, i.e. edges will be propagated from level
     *                 {@code height} to {@code height + 1}.
     * @throws SQLException if there are issues executing the queries.
     */
    private void linkAnalysisOffSpecificEdgePropagation(Statement statement, int height) throws SQLException {
        // Propagate all edges to the next level.
        statement.execute(
            "INSERT IGNORE INTO InheritedEdges " +
                "SELECT DISTINCT " +
                    "lattice_rel.child AS Rchain, " +
                    "Path_BayesNets.child AS child, " +
                    "Path_BayesNets.parent AS parent " +
                "FROM " +
                    "Path_BayesNets, " +
                    this.dbInfo.getSetupDatabaseName() + ".lattice_rel, " +
                    this.dbInfo.getSetupDatabaseName() + ".lattice_set " +
                "WHERE " +
                    "lattice_rel.parent = Path_BayesNets.Rchain " +
                "AND " +
                    "Path_BayesNets.parent <> '' " +
                "AND " +
                    "lattice_set.name = lattice_rel.parent " +
                "AND " +
                    "lattice_set.length = " + height + " " +
                "ORDER BY " +
                    "Rchain;"
        );

        // Kurt: Alternate LearnedEdges.
        statement.execute(
            "INSERT IGNORE INTO NewLearnedEdges " +
                "SELECT " +
                    "Path_BayesNets.Rchain, " +
                    "Path_BayesNets.child, " +
                    "Path_BayesNets.parent " +
                "FROM " +
                    "Path_BayesNets, " +
                    this.dbInfo.getSetupDatabaseName() + ".lattice_set " +
                "WHERE " +
                    "Path_BayesNets.parent <> '' " +
                "AND " +
                    "Path_BayesNets.Rchain = lattice_set.name " +
                "AND " +
                    "lattice_set.length = " + height + " " +
                "AND (" +
                    "Path_BayesNets.Rchain, " +
                    "Path_BayesNets.child, " +
                    "Path_BayesNets.parent" +
                ") NOT IN (" +
                    "SELECT " +
                        "* " +
                    "FROM " +
                        "Path_Required_Edges" +
                ");"
        );

        statement.execute(
            "INSERT IGNORE INTO InheritedEdges " +
                "SELECT DISTINCT " +
                    "NewLearnedEdges.Rchain AS Rchain, " +
                    "NewLearnedEdges.child AS child, " +
                    "lattice_membership.member AS parent " +
                "FROM " +
                    "NewLearnedEdges, " +
                    this.dbInfo.getSetupDatabaseName() + ".lattice_membership " +
                "WHERE " +
                    "NewLearnedEdges.Rchain = lattice_membership.name;"
        );

        statement.execute(
            "INSERT IGNORE INTO Path_BayesNets " +
                "SELECT " +
                    "* " +
                "FROM " +
                    "InheritedEdges;"
        );
    }
}