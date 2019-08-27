package ca.sfu.cs.factorbase.util;

import java.util.HashMap;
import java.util.Map;

/**
 * Class to generate Map objects used by FactorBase.
 */
public final class Mapper {
    private static StringBuilder builder = new StringBuilder();

    /**
     * Private constructor to prevent instantiation of the utility class.
     */
    private Mapper() {
    }


    /**
     * Generate a Map that has key:value pairs of column-name:column-index.
     *
     * @param header - header from the dataset.
     * @return Map object that maps each column name to its associated column index.
     */
    public static Map<String, Integer> mapHeadersToColumnIndices(String[] header) {
        Map<String, Integer> headerToColumnIndex = new HashMap<String, Integer>();

        // for loop to create a Map that has key:value pairs of column-name:column-index.
        for (int index = 0; index < header.length; index++) {
            headerToColumnIndex.put(header[index], index);
        }

        return headerToColumnIndex;
    }


    /**
     * Generate the Map key for a variable when it is assigned a particular value.  This is the key that should be used
     * to retrieve a value from the Map generated by the {@code Mapper.mapVariableStateToInteger()} method.
     *
     * @param variable - the name of the variable being assigned a particular value.
     * @param state - the state being assigned to the variable given.
     * @return a key to retrieve the integer encoding for the variable assignment.
     */
    public static String generateVariableStateKey(String variable, String state) {
        builder.setLength(0);
        builder.append(variable);
        builder.append(" = ");
        builder.append(state);
        return builder.toString();
    }
}