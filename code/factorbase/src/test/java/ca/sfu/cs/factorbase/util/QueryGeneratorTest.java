package ca.sfu.cs.factorbase.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashSet;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import testframework.TestDatabaseConnection;


/**
 * Tests for the file QueryGenerator.java.
 *
 * Note: It is assumed that the script, tests-database.sql, which is found in the testsql
 * directory, has been run already.
 */
public class QueryGeneratorTest {

    private static final String SINGLE_COLUMN_INSERTS_TABLE = "`single-column-inserts`";
    private static TestDatabaseConnection db;
    private static Statement st;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        db = new TestDatabaseConnection();
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        db.con.close();
        db = null;
    }

    @Before
    public void setUp() throws Exception {
        st = db.con.createStatement();
    }

    @After
    public void tearDown() throws Exception {
        st.close();
        st = null;
    }

    @Test
    public void createDifferenceQuery_ReturnsCorrectResults() throws SQLException {
        String query = QueryGenerator.createDifferenceQuery(
                "s_id,attr1,attr2,attr3",
                Arrays.asList("s_id", "attr1", "attr2"),
                "t1",
                "t2"
        );

        ResultSet rs = st.executeQuery(query);

        int count = 0;
        while (rs.next()) {
            switch (count) {
            case 0:
                assertThat(rs.getString(1), equalTo("A"));
                break;
            case 1:
                assertThat(rs.getString(1), equalTo("Jack2"));
                break;
            case 2:
                assertThat(rs.getString(1), equalTo("Kim"));
                break;
            case 3:
                assertThat(rs.getString(1), equalTo("Paul"));
                break;
            case 4:
                assertThat(rs.getString(1), equalTo("Paul2"));
                break;
            }
            count++;
        }

        assertThat(rs.getMetaData().getColumnCount(), equalTo(4));
        assertThat(count, equalTo(5));

        rs.close();
    }

    @Test
    public void createSimpleInQuery_ReturnsCorrectResults_WhenSingleMatch() throws SQLException {
        String query = QueryGenerator.createSimpleInQuery(
            "t1",
            "s_id",
            Arrays.asList("Jack")
        );

        ResultSet rs = st.executeQuery(query);

        rs.next();
        assertThat(rs.getString(1), equalTo("Jack"));
        assertThat(rs.getMetaData().getColumnCount(), equalTo(4));
        assertThat(rs.isLast(), is(true));

        rs.close();
    }

    @Test
    public void createSimpleInQuery_ReturnsCorrectResults_WhenMultipleMatches() throws SQLException {
        String query = QueryGenerator.createSimpleInQuery(
            "t1",
            "s_id",
            Arrays.asList("Jack", "Kim", "Paul")
        );

        ResultSet rs = st.executeQuery(query);

        int count = 0;
        while (rs.next()) {
            switch (count) {
            case 0:
                assertThat(rs.getString(1), equalTo("Jack"));
                break;
            case 1:
                assertThat(rs.getString(1), equalTo("Kim"));
                break;
            case 2:
                assertThat(rs.getString(1), equalTo("Paul"));
                break;
            }
            count++;
        }

        assertThat(count, equalTo(3));

        rs.close();
    }

    @Test
    public void createSimpleExtendedInsertQuery_InsertsResults_WhenNoParentsGiven() throws SQLException {
        String query = QueryGenerator.createSimpleExtendedInsertQuery(
            SINGLE_COLUMN_INSERTS_TABLE,
            "child",
            new HashSet<String>()
        );

        int numberOfInserts = st.executeUpdate(query);
        assertThat(numberOfInserts, equalTo(1));

        // Clean up the inserted values.
        truncateTable(SINGLE_COLUMN_INSERTS_TABLE);
    }

    @Test
    public void createSimpleExtendedInsertQuery_InsertsResults_WhenOneParentGiven() throws SQLException {
        String query = QueryGenerator.createSimpleExtendedInsertQuery(
            SINGLE_COLUMN_INSERTS_TABLE,
            "child",
            new HashSet<String>(Arrays.asList("parent"))
        );

        int numberOfInserts = st.executeUpdate(query);
        assertThat(numberOfInserts, equalTo(2));

        // Clean up the inserted values.
        truncateTable(SINGLE_COLUMN_INSERTS_TABLE);
    }

    @Test
    public void createSimpleExtendedInsertQuery_InsertsResults_WhenMultipleParentsGiven() throws SQLException {
        String query = QueryGenerator.createSimpleExtendedInsertQuery(
            SINGLE_COLUMN_INSERTS_TABLE,
            "child",
            new HashSet<String>(Arrays.asList("parent1", "parent2"))
        );

        int numberOfInserts = st.executeUpdate(query);
        assertThat(numberOfInserts, equalTo(3));

        // Clean up the inserted values.
        truncateTable(SINGLE_COLUMN_INSERTS_TABLE);
    }

    private void truncateTable(String tableToTruncate) throws SQLException {
        String query = QueryGenerator.createTruncateQuery(tableToTruncate);
        st.executeUpdate(query);
    }
}