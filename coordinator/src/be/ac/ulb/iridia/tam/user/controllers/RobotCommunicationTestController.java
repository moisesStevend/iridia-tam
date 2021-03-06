package be.ac.ulb.iridia.tam.user.controllers;

import be.ac.ulb.iridia.tam.common.AbstractController;
import be.ac.ulb.iridia.tam.common.LedColor;
import be.ac.ulb.iridia.tam.common.TAMInterface;
import org.apache.log4j.Logger;


/**
 * This controller is used to test the communication to and from a robot.
 * The controller checks if there is a robot in the tam:
 *  - it reads its id; 
 *  - it writes a random number to it;
 *  - it then reads back this value;
 */
public class RobotCommunicationTestController extends AbstractController
{
    // colors of the RGB leds used (0x19 max value to keep eyes safe)
    public final static LedColor LED_RED   = new LedColor(0x19000000);
    public final static LedColor LED_GREEN = new LedColor(0x00190000);
    public final static LedColor LED_BLUE  = new LedColor(0x00001900);
    public final static LedColor LED_OFF   = new LedColor(0x00000000);
	
    private final static Logger log = Logger.getLogger(RobotCommunicationTestController.class);

    enum TAMState
    {
    	READ_ID,
    	WRITE_RAND,
    	READ_RAND,
    	DONE
    }

    // current state of the FSM that controls the TAM
    private TAMState currentState;
    // TAM this controller is attached two
    private TAMInterface tam;


    /**
     * Sets up the controller.
     * @param randomSeed   seed for the prng
     * @param tam          TAM this controller should be attached to
     */
    public void init(long randomSeed, TAMInterface tam)
    {
        super.init(randomSeed);
        this.tam = tam;
        this.currentState = TAMState.READ_ID;

        log.info("New TAM controller, starting in task READ_ID");
    }

    /**
     * Step function of the controller. Called every Coordinator.STEP_INTERVAL milliseconds.
     */
    public void step()
    {
        log.debug("============== Stepping "+tam.getId()+" currentState "+currentState+ " ================");
        switch (getState())
        {
        
        case READ_ID:
	        if(tam.isRobotPresent()){ // if there is a robot in the tam
	        	//try to read a value from it
	        	log.info("ID from robot: " + tam.getRobotDataReceived());
	        	if (tam.getRobotDataReceived() == 5)
	        		currentState = TAMState.WRITE_RAND;
	        }
	        break;
	     
        case WRITE_RAND:	       
        	if(tam.isRobotPresent()){ // if there is a robot in the tam
                int randomValue = 37;
        		//sends a random value between 0 and 10       
        		log.info("Sending " + randomValue +" to robot... ");
                tam.setRobotDataToSend(randomValue);
        		log.info("...done");
        		currentState = TAMState.READ_RAND;
        	}
	        break;
	        
        case READ_RAND:
        	if(tam.isRobotPresent()){ // if there is a robot in the tam
	        	//try to read a value from it
        		int value = tam.getRobotDataReceived();
	        	log.info("Random value from robot: " + value);
	        	if (value == 37){
	        		// it works
	        		log.info("Test success");
                    tam.setLedColor(LED_GREEN);
	        		currentState = TAMState.DONE;
	        	}
	        }
	        break;
	        
        case DONE:	       
        	log.info("Experiment done");
	        break;
        }
        log.debug("==============================");
        
    }
    
    /**
     * Returns the current state of the TAM.
     * @return state of TAM as enum TAMState
     */
    public TAMState getState()
    {
        return currentState;
    }

}