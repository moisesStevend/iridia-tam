package be.ac.ulb.iridia.tam.user.experiments;

import be.ac.ulb.iridia.tam.common.AbstractExperiment;
import be.ac.ulb.iridia.tam.coordinator.Coordinator;
import be.ac.ulb.iridia.tam.common.ExperimentInterface;
import be.ac.ulb.iridia.tam.common.TAMInterface;
import be.ac.ulb.iridia.tam.user.controllers.CameraCalibrationController;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

import java.util.TimerTask;


/**
 * This is a test experiment to calibrate the camera of the epuck robots.
 * The controller simply keeps the LEDs of the TAM on with a red color.
 * @see CameraCalibrationController
 */
public class CameraCalibrationExperiment extends AbstractExperiment
{
    private final static Logger log = Logger.getLogger(CameraCalibrationExperiment.class);

    private final static long EXPERIMENT_DURATION_IN_SECONDS = 600000000;


    /**
     * Initializes experiment.
     * Sets flags to defaults and initialized the random number generator.
     * @param randomSeed  seed for the prng, set either constant or use System.currentTimeMillis()
     */
    @Override
    public void init(long randomSeed)
    {
        super.init(randomSeed);

        // request shutdown after EXPERIMENT_DURATION_IN_SECONDS seconds
        getTimer().schedule(new TimerTask()
        {
            @Override
            public void run()
            {
                log.fatal("Experiment duration is over, terminating.");
                setFinished();
            }
        }, EXPERIMENT_DURATION_IN_SECONDS * 1000);
    }

    /**
     * Called by the coordinator to attach controllers to newly discovered TAMs.
     * You can use the id or address of the TAM to attach specific controllers
     * to specific TAMs, thereby giving them the different functionality.
     * @param tam  TAM the coordinator requests a controller for
     */
    public void attachTAMController(TAMInterface tam)
    {
        // create new controller for a tam
        log.info("Creating new CameraCalibrationController for " + tam.getId());
        CameraCalibrationController controller = new CameraCalibrationController();
        controller.init(getPrng().nextInt(), tam);
        tam.setController(controller);
    }

    /**
     * Main function used to start the experiment.
     * @param args  array of command line arguments
     * @throws Exception on Xbee comm failure
     */
    public static void main(String[] args) throws Exception
    {
        // init log4j
        PropertyConfigurator.configure("log4j.properties");

        // create the coordinator
        Coordinator coordinator = new Coordinator("/dev/ttyUSB0", 9600);

        // create our experiment (see above)
        ExperimentInterface experiment = new CameraCalibrationExperiment();
        experiment.init(System.currentTimeMillis());
        coordinator.setExperiment(experiment);

        // run the coordinator send and receive threads that handle all Xbee communication
        // NOTE: this will never return, so must be last!
        coordinator.start();
    }
}
