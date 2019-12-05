package info.nightscout.androidaps.plugins.pump.omnipod.util;

import org.joda.time.Duration;

/**
 * Created by andy on 4.8.2019
 */

public class OmnipodConst {

    static final String Prefix = "AAPS.Omnipod.";

    public class Prefs {
        public static final String PodState = Prefix + "pod_state";
    }

    public class Statistics {
        public static final String StatsPrefix = "omnipod_";
        public static final String FirstPumpStart = Prefix + "first_pump_use";
        public static final String LastGoodPumpCommunicationTime = Prefix + "lastGoodPumpCommunicationTime";
        public static final String LastGoodPumpFrequency = Prefix + "LastGoodPumpFrequency";
        public static final String TBRsSet = StatsPrefix + "tbrs_set";
        public static final String StandardBoluses = StatsPrefix + "std_boluses_delivered";
        public static final String SMBBoluses = StatsPrefix + "smb_boluses_delivered";
        public static final String LastPumpHistoryEntry = StatsPrefix + "pump_history_entry";
    }

    public static final double POD_PULSE_SIZE = 0.05;
    public static final double POD_BOLUS_DELIVERY_RATE = 0.025; // units per second
    public static final double POD_PRIMING_DELIVERY_RATE = 0.05; // units per second
    public static final double POD_CANNULA_INSERTION_DELIVERY_RATE = 0.05; // units per second
    public static final double MAX_RESERVOIR_READING = 50.0;
    public static final double MAX_BOLUS = 30.0;
    public static final double MAX_BASAL_RATE = 30.0;
    public static final Duration MAX_TEMP_BASAL_DURATION = Duration.standardHours(12);
    public static final int DEFAULT_ADDRESS = 0xffffffff;

    public static final Duration AVERAGE_BOLUS_COMMAND_COMMUNICATION_DURATION = Duration.standardSeconds(2);

    public static final Duration SERVICE_DURATION = Duration.standardHours(80);
    public static final Duration EXPIRATION_ADVISORY_WINDOW = Duration.standardHours(2);
    public static final Duration END_OF_SERVICE_IMMINENT_WINDOW = Duration.standardHours(1);
    public static final Duration NOMINAL_POD_LIFE = SERVICE_DURATION.minus(END_OF_SERVICE_IMMINENT_WINDOW).minus(EXPIRATION_ADVISORY_WINDOW);

    public static final double POD_PRIME_BOLUS_UNITS = 2.6;
    public static final double POD_CANNULA_INSERTION_BOLUS_UNITS = 0.5;
}
