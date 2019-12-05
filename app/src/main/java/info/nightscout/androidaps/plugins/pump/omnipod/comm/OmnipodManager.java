package info.nightscout.androidaps.plugins.pump.omnipod.comm;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.pump.common.data.TempBasalPair;
import info.nightscout.androidaps.plugins.pump.omnipod.comm.action.AcknowledgeAlertsAction;
import info.nightscout.androidaps.plugins.pump.omnipod.comm.action.BolusAction;
import info.nightscout.androidaps.plugins.pump.omnipod.comm.action.CancelDeliveryAction;
import info.nightscout.androidaps.plugins.pump.omnipod.comm.action.DeactivatePodAction;
import info.nightscout.androidaps.plugins.pump.omnipod.comm.action.GetPodInfoAction;
import info.nightscout.androidaps.plugins.pump.omnipod.comm.action.GetStatusAction;
import info.nightscout.androidaps.plugins.pump.omnipod.comm.action.InsertCannulaAction;
import info.nightscout.androidaps.plugins.pump.omnipod.comm.action.PairAction;
import info.nightscout.androidaps.plugins.pump.omnipod.comm.action.PrimeAction;
import info.nightscout.androidaps.plugins.pump.omnipod.comm.action.SetBasalScheduleAction;
import info.nightscout.androidaps.plugins.pump.omnipod.comm.action.SetTempBasalAction;
import info.nightscout.androidaps.plugins.pump.omnipod.comm.action.service.InsertCannulaService;
import info.nightscout.androidaps.plugins.pump.omnipod.comm.action.service.PairService;
import info.nightscout.androidaps.plugins.pump.omnipod.comm.action.service.PrimeService;
import info.nightscout.androidaps.plugins.pump.omnipod.comm.action.service.SetTempBasalService;
import info.nightscout.androidaps.plugins.pump.omnipod.comm.message.command.CancelDeliveryCommand;
import info.nightscout.androidaps.plugins.pump.omnipod.comm.message.response.StatusResponse;
import info.nightscout.androidaps.plugins.pump.omnipod.comm.message.response.podinfo.PodInfoResponse;
import info.nightscout.androidaps.plugins.pump.omnipod.defs.BeepType;
import info.nightscout.androidaps.plugins.pump.omnipod.defs.DeliveryStatus;
import info.nightscout.androidaps.plugins.pump.omnipod.defs.DeliveryType;
import info.nightscout.androidaps.plugins.pump.omnipod.defs.PodInfoType;
import info.nightscout.androidaps.plugins.pump.omnipod.defs.SetupProgress;
import info.nightscout.androidaps.plugins.pump.omnipod.defs.schedule.BasalSchedule;
import info.nightscout.androidaps.plugins.pump.omnipod.defs.state.PodSessionState;
import info.nightscout.androidaps.plugins.pump.omnipod.defs.state.PodStateChangedHandler;
import info.nightscout.androidaps.plugins.pump.omnipod.exception.CommunicationException;
import info.nightscout.androidaps.plugins.pump.omnipod.exception.IllegalDeliveryStatusException;
import info.nightscout.androidaps.plugins.pump.omnipod.exception.IllegalSetupProgressException;
import info.nightscout.androidaps.plugins.pump.omnipod.exception.NonceOutOfSyncException;
import info.nightscout.androidaps.plugins.pump.omnipod.exception.OmnipodException;
import info.nightscout.androidaps.plugins.pump.omnipod.util.OmnipodConst;
import info.nightscout.androidaps.utils.SP;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.subjects.SingleSubject;

import static info.nightscout.androidaps.plugins.pump.omnipod.util.OmnipodConst.AVERAGE_BOLUS_COMMAND_COMMUNICATION_DURATION;

public class OmnipodManager {
    private static final int ACTION_VERIFICATION_TRIES = 3;

    private static final Logger LOG = LoggerFactory.getLogger(L.PUMP);

    protected final OmnipodCommunicationService communicationService;
    private final PodStateChangedHandler podStateChangedHandler;
    protected PodSessionState podState;

    private ActiveBolusData activeBolusData;
    private final Object bolusDataLock = new Object();

    public OmnipodManager(OmnipodCommunicationService communicationService, PodSessionState podState,
                          PodStateChangedHandler podStateChangedHandler) {
        if (communicationService == null) {
            throw new IllegalArgumentException("Communication service cannot be null");
        }
        this.communicationService = communicationService;
        if (podState != null) {
            podState.setStateChangedHandler(podStateChangedHandler);
        }
        this.podState = podState;
        this.podStateChangedHandler = podStateChangedHandler;
    }

    public OmnipodManager(OmnipodCommunicationService communicationService, PodSessionState podState) {
        this(communicationService, podState, null);
    }

    public synchronized Single<SetupActionResult> pairAndPrime() {
        if (podState == null) {
            podState = communicationService.executeAction(
                    new PairAction(new PairService(), podStateChangedHandler));
        }
        if (!podState.getSetupProgress().isBefore(SetupProgress.PRIMING_FINISHED)) {
            throw new IllegalSetupProgressException(SetupProgress.ADDRESS_ASSIGNED, podState.getSetupProgress());
        }

        communicationService.executeAction(new PrimeAction(new PrimeService(), podState));

        long delayInSeconds = calculateBolusDuration(OmnipodConst.POD_PRIME_BOLUS_UNITS, OmnipodConst.POD_PRIMING_DELIVERY_RATE).getStandardSeconds();

        return Single.timer(delayInSeconds, TimeUnit.SECONDS) //
                .map(o -> verifySetupAction(statusResponse ->
                        PrimeAction.updatePrimingStatus(podState, statusResponse), SetupProgress.PRIMING_FINISHED)) //
                .observeOn(AndroidSchedulers.mainThread());
    }

    public synchronized Single<SetupActionResult> insertCannula(BasalSchedule basalSchedule) {
        if (podState == null || podState.getSetupProgress().isBefore(SetupProgress.PRIMING_FINISHED)) {
            throw new IllegalSetupProgressException(SetupProgress.PRIMING_FINISHED, podState == null ? null : podState.getSetupProgress());
        } else if (podState.getSetupProgress().isAfter(SetupProgress.CANNULA_INSERTING)) {
            throw new IllegalSetupProgressException(SetupProgress.CANNULA_INSERTING, podState.getSetupProgress());
        }

        communicationService.executeAction(new InsertCannulaAction(new InsertCannulaService(), podState, basalSchedule));

        long delayInSeconds = calculateBolusDuration(OmnipodConst.POD_CANNULA_INSERTION_BOLUS_UNITS, OmnipodConst.POD_CANNULA_INSERTION_DELIVERY_RATE).getStandardSeconds();
        return Single.timer(delayInSeconds, TimeUnit.SECONDS) //
                .map(o -> verifySetupAction(statusResponse ->
                        InsertCannulaAction.updateCannulaInsertionStatus(podState, statusResponse), SetupProgress.COMPLETED)) //
                .observeOn(AndroidSchedulers.mainThread());
    }

    public synchronized StatusResponse getPodStatus() {
        if (podState == null) {
            throw new IllegalSetupProgressException(SetupProgress.PRIMING_FINISHED, null);
        }

        return communicationService.executeAction(new GetStatusAction(podState));
    }

    public synchronized PodInfoResponse getPodInfo(PodInfoType podInfoType) {
        assertReadyForDelivery();

        return communicationService.executeAction(new GetPodInfoAction(podState, podInfoType));
    }

    public synchronized void acknowledgeAlerts() {
        assertReadyForDelivery();

        communicationService.executeAction(new AcknowledgeAlertsAction(podState, podState.getActiveAlerts()));
    }

    public synchronized void setBasalSchedule(BasalSchedule schedule) {
        assertReadyForDelivery();

        communicationService.executeAction(new SetBasalScheduleAction(podState, schedule,
                false, podState.getScheduleOffset(), true));
    }

    public synchronized void setTemporaryBasal(TempBasalPair tempBasalPair) {
        assertReadyForDelivery();

        communicationService.executeAction(new SetTempBasalAction(new SetTempBasalService(),
                podState, tempBasalPair.getInsulinRate(), Duration.standardMinutes(tempBasalPair.getDurationMinutes()),
                true, true));
    }

    public synchronized void cancelTemporaryBasal() {
        assertReadyForDelivery();

        communicationService.executeAction(new CancelDeliveryAction(podState, DeliveryType.TEMP_BASAL, true));
    }

    // Returns a SingleSubject that returns when the bolus has finished.
    // When a bolus is cancelled, it will return after cancellation and report the estimated units delivered
    public synchronized SingleSubject<BolusResult> bolus(Double units, BolusProgressIndicationConsumer progressIndicationConsumer) {
        assertReadyForDelivery();

        executeAndVerify(() -> communicationService.executeAction(new BolusAction(podState, units, true, true)));

        DateTime startDate = DateTime.now().minus(AVERAGE_BOLUS_COMMAND_COMMUNICATION_DURATION);

        CompositeDisposable disposables = new CompositeDisposable();
        Duration bolusDuration = calculateBolusDuration(units, OmnipodConst.POD_BOLUS_DELIVERY_RATE);
        Duration estimatedRemainingBolusDuration = bolusDuration.minus(AVERAGE_BOLUS_COMMAND_COMMUNICATION_DURATION);

        if (progressIndicationConsumer != null) {
            int numberOfProgressReports = 20;
            long progressReportInterval = estimatedRemainingBolusDuration.getMillis() / numberOfProgressReports;

            disposables.add(Flowable.intervalRange(1, numberOfProgressReports, 0, progressReportInterval, TimeUnit.MILLISECONDS) //
                    .subscribe(count -> {
                        int percentage = (int) ((double) count / numberOfProgressReports * 100);
                        double estimatedUnitsDelivered = activeBolusData == null ? 0 : activeBolusData.estimateUnitsDelivered();
                        progressIndicationConsumer.accept(estimatedUnitsDelivered, percentage);
                    }));
        }

        SingleSubject<BolusResult> bolusCompletionSubject = SingleSubject.create();

        disposables.add(Completable.complete()
                .delay(estimatedRemainingBolusDuration.getStandardSeconds(), TimeUnit.SECONDS)
                .doOnComplete(() -> {
                    synchronized (bolusDataLock) {
                        for (int i = 0; i < ACTION_VERIFICATION_TRIES; i++) {
                            try {
                                // Retrieve a status response in order to update the pod state
                                StatusResponse statusResponse = getPodStatus();
                                if (statusResponse.getDeliveryStatus().isBolusing()) {
                                    throw new IllegalDeliveryStatusException(DeliveryStatus.NORMAL, statusResponse.getDeliveryStatus());
                                } else {
                                    break;
                                }
                            } catch (Exception ex) {
                                if (isLoggingEnabled()) {
                                    LOG.debug("Ignoring exception in bolus completion verfication", ex);
                                }
                            }
                        }

                        if (activeBolusData != null) {
                            activeBolusData.bolusCompletionSubject.onSuccess(new BolusResult(units));
                            activeBolusData = null;
                        }
                    }
                })
                .subscribe());

        synchronized (bolusDataLock) {
            activeBolusData = new ActiveBolusData(units, startDate, bolusCompletionSubject, disposables);
        }

        return bolusCompletionSubject;
    }

    public synchronized void cancelBolus() {
        assertReadyForDelivery();

        synchronized (bolusDataLock) {
            if (activeBolusData == null) {
                throw new IllegalDeliveryStatusException(DeliveryStatus.BOLUS_IN_PROGRESS, podState.getLastDeliveryStatus());
            }

            executeAndVerify(() -> communicationService.executeAction(new CancelDeliveryAction(podState, DeliveryType.BOLUS, true)));

            activeBolusData.getDisposables().dispose();
            activeBolusData.getBolusCompletionSubject().onSuccess(new BolusResult(activeBolusData.estimateUnitsDelivered()));
            activeBolusData = null;
        }
    }

    public synchronized void suspendDelivery() {
        assertReadyForDelivery();

        communicationService.executeAction(new CancelDeliveryAction(podState, EnumSet.allOf(DeliveryType.class), true));
    }

    public synchronized void resumeDelivery() {
        assertReadyForDelivery();

        communicationService.executeAction(new SetBasalScheduleAction(podState, podState.getBasalSchedule(),
                true, podState.getScheduleOffset(), true));
    }

    // If this command fails, it it possible that delivery has been suspended
    public synchronized void setTime() {
        assertReadyForDelivery();

        // Suspend delivery
        communicationService.executeAction(new CancelDeliveryAction(podState, EnumSet.allOf(DeliveryType.class), false));

        // Joda seems to cache the default time zone, so we use the JVM's
        DateTimeZone.setDefault(DateTimeZone.forTimeZone(TimeZone.getDefault()));
        podState.setTimeZone(DateTimeZone.getDefault());

        // Resume delivery
        StatusResponse statusResponse = communicationService.executeAction(new SetBasalScheduleAction(podState, podState.getBasalSchedule(),
                true, podState.getScheduleOffset(), true));
    }

    public synchronized void deactivatePod() {
        if (podState == null) {
            throw new IllegalSetupProgressException(SetupProgress.ADDRESS_ASSIGNED, null);
        }

        communicationService.executeAction(new DeactivatePodAction(podState, true));
        resetPodState();
    }

    public void resetPodState() {
        podState = null;
        SP.remove(OmnipodConst.Prefs.PodState);
    }

    public OmnipodCommunicationService getCommunicationService() {
        return communicationService;
    }

    public DateTime getTime() {
        return podState.getTime();
    }

    public boolean isReadyForDelivery() {
        return podState != null && podState.getSetupProgress() == SetupProgress.COMPLETED;
    }

    public PodSessionState getPodState() {
        return this.podState;
    }

    public String getPodStateAsString() {
        return podState == null ? "null" : podState.toString();
    }

    // Only works for commands with nonce resyncable message blocks
    private void executeAndVerify(Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception ex) {
            if (isCertainFailure(ex)) {
                throw ex;
            } else {
                CommandVerificationResult verificationResult = verifyCommand();
                switch (verificationResult) {
                    case CERTAIN_FAILURE:
                        if (ex instanceof OmnipodException) {
                            ((OmnipodException) ex).setCertainFailure(true);
                            throw ex;
                        } else {
                            OmnipodException newException = new CommunicationException(CommunicationException.Type.UNEXPECTED_EXCEPTION, ex);
                            newException.setCertainFailure(true);
                            throw newException;
                        }
                    case UNCERTAIN_FAILURE:
                        throw ex;
                    case SUCCESS:
                        // Ignore original exception
                        break;
                }
            }
        }
    }

    private void assertReadyForDelivery() {
        if (!isReadyForDelivery()) {
            throw new IllegalSetupProgressException(SetupProgress.COMPLETED, podState == null ? null : podState.getSetupProgress());
        }
    }

    private SetupActionResult verifySetupAction(StatusResponseHandler setupActionResponseHandler, SetupProgress expectedSetupProgress) {
        SetupActionResult result = null;
        for (int i = 0; ACTION_VERIFICATION_TRIES > i; i++) {
            try {
                StatusResponse delayedStatusResponse = communicationService.executeAction(new GetStatusAction(podState));
                setupActionResponseHandler.handle(delayedStatusResponse);

                if (podState.getSetupProgress().equals(expectedSetupProgress)) {
                    result = new SetupActionResult(SetupActionResult.ResultType.SUCCESS);
                    break;
                } else {
                    result = new SetupActionResult(SetupActionResult.ResultType.FAILURE) //
                            .setupProgress(podState.getSetupProgress());
                    break;
                }
            } catch (Exception ex) {
                result = new SetupActionResult(SetupActionResult.ResultType.VERIFICATION_FAILURE) //
                        .exception(ex);
            }
        }
        return result;
    }

    // Only works for commands which contain nonce resyncable message blocks
    private CommandVerificationResult verifyCommand() {
        if (isLoggingEnabled()) {
            LOG.warn("Verifying command by using cancel none command to verify nonce");
        }
        try {
            communicationService.sendCommand(StatusResponse.class, podState,
                    new CancelDeliveryCommand(podState.getCurrentNonce(), BeepType.NO_BEEP, DeliveryType.NONE), false);
        } catch (NonceOutOfSyncException ex) {
            if (isLoggingEnabled()) {
                LOG.info("Command resolved to FAILURE (CERTAIN_FAILURE)");
            }
            return CommandVerificationResult.CERTAIN_FAILURE;
        } catch (Exception ex) {
            if (isLoggingEnabled()) {
                LOG.error("Command unresolved (UNCERTAIN_FAILURE)");
            }
            return CommandVerificationResult.UNCERTAIN_FAILURE;
        }

        if (isLoggingEnabled()) {
            if (isLoggingEnabled()) {
                LOG.info("Command status resolved to SUCCESS");
            }
        }
        return CommandVerificationResult.SUCCESS;
    }

    private boolean isLoggingEnabled() {
        return L.isEnabled(L.PUMP);
    }

    private static Duration calculateBolusDuration(double units, double deliveryRate) {
        return Duration.standardSeconds((long) Math.ceil(units / deliveryRate));
    }

    public static Duration calculateBolusDuration(double units) {
        return calculateBolusDuration(units, OmnipodConst.POD_BOLUS_DELIVERY_RATE);
    }

    public static boolean isCertainFailure(Exception ex) {
        return ex instanceof OmnipodException && ((OmnipodException) ex).isCertainFailure();
    }

    public static class BolusResult {
        private final double unitsDelivered;

        public BolusResult(double unitsDelivered) {
            this.unitsDelivered = unitsDelivered;
        }

        public double getUnitsDelivered() {
            return unitsDelivered;
        }
    }

    private enum CommandVerificationResult {
        SUCCESS,
        CERTAIN_FAILURE,
        UNCERTAIN_FAILURE
    }

    // TODO replace with Consumer when our min API level >= 24
    @FunctionalInterface
    private interface StatusResponseHandler {
        void handle(StatusResponse statusResponse);
    }

    private static class ActiveBolusData {
        private final double units;
        private volatile DateTime startDate;
        private volatile SingleSubject<BolusResult> bolusCompletionSubject;
        private volatile CompositeDisposable disposables;

        private ActiveBolusData(double units, DateTime startDate, SingleSubject<BolusResult> bolusCompletionSubject, CompositeDisposable disposables) {
            this.units = units;
            this.startDate = startDate;
            this.bolusCompletionSubject = bolusCompletionSubject;
            this.disposables = disposables;
        }

        public double getUnits() {
            return units;
        }

        public DateTime getStartDate() {
            return startDate;
        }

        public CompositeDisposable getDisposables() {
            return disposables;
        }

        public SingleSubject<BolusResult> getBolusCompletionSubject() {
            return bolusCompletionSubject;
        }

        public void setBolusCompletionSubject(SingleSubject<BolusResult> bolusCompletionSubject) {
            this.bolusCompletionSubject = bolusCompletionSubject;
        }

        public double estimateUnitsDelivered() {
            // TODO this needs improvement
            //  take (average) radio communication time into account
            long elapsedMillis = new Duration(startDate, DateTime.now()).getMillis();
            long totalDurationMillis = (long) (units / OmnipodConst.POD_BOLUS_DELIVERY_RATE * 1000);
            double factor = (double) elapsedMillis / totalDurationMillis;
            double estimatedUnits = Math.min(1D, factor) * units;

            int roundingDivisor = (int) (1 / OmnipodConst.POD_PULSE_SIZE);
            return (double) Math.round(estimatedUnits * roundingDivisor) / roundingDivisor;
        }
    }
}
