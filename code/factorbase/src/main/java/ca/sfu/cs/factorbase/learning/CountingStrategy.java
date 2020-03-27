package ca.sfu.cs.factorbase.learning;


/**
 * enum to help toggle features during FactorBase execution.
 */
public enum CountingStrategy {
    PreCount {
        @Override
        public boolean isPrecount() {
            return true;
        }
    },
    OnDemand {
        @Override
        public boolean isOndemand() {
            return true;
        }
    },
    Hybrid {
        @Override
        public boolean isHybrid() {
            return true;
        }


        @Override
        public boolean useProjection() {
            return true;
        }
    };

    /**
     * Determine if the counting strategy is precount.
     *
     * @return true if the counting strategy is precount; otherwise false.
     */
    public boolean isPrecount() {
        return false;
    }


    /**
     * Determine if the counting strategy is ondemand.
     *
     * @return true if the counting strategy is ondemand; otherwise false.
     */
    public boolean isOndemand() {
        return false;
    }


    /**
     * Determine if the counting strategy is hybrid.
     *
     * @return true if the counting strategy is hybrid; otherwise false.
     */
    public boolean isHybrid() {
        return false;
    }


    /**
     * Determine if the local counts should be generated by projecting the count information from the global
     * contingency tables.
     *
     * @return true if the counts should be generated by projecting from the global counts tables; otherwise false.
     */
    public boolean useProjection() {
        return false;
    }

    private static final String PRECOUNT = "0";
    private static final String ONDEMAND = "1";
    private static final String HYBRID = "2";


    /**
     * Determine what counting strategy should be used based on the given configuration file setting.
     *
     * @param configurationValue - the counting strategy setting given in the configuration file.
     * @return {@code CountingStrategy} that has been set in the configuration file.
     */
    public static CountingStrategy determineStrategy(String configurationValue) {
        CountingStrategy strategy;
        switch (configurationValue) {
        case PRECOUNT:
            strategy = PreCount;
            break;
        case ONDEMAND:
            strategy = OnDemand;
            break;
        case HYBRID:
            strategy = Hybrid;
            break;
        default:
            strategy = null;
        }

        return strategy;
    }
}