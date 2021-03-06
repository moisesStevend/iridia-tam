package be.ac.ulb.iridia.tam.user.controllers;

import be.ac.ulb.iridia.tam.common.AbstractController;
import be.ac.ulb.iridia.tam.common.LedColor;

import be.ac.ulb.iridia.tam.common.TAMInterface;
import org.apache.log4j.Logger;


/**
 * This example controller implements a TAM that is independent of other TAMs.
 * The TAM can represent one of two tasks, BLUE or GREEN, each running with a different
 * duration. The controller sets a random task when the TAM is free.
 */
public class TaskSequencingController extends AbstractController
{
    private final static Logger log = Logger.getLogger(TaskSequencingController.class);
    
    // colors of the RGB leds used (0x19 max value to keep eyes safe)
    public final static LedColor LED_RED   = new LedColor(0x11000700);
    public final static LedColor LED_GREEN = new LedColor(0x070C0000);
    public final static LedColor LED_BLUE  = new LedColor(0x07000900);
    
    public final static LedColor LED_TEST = new LedColor(0x0000FF00);
    
    public final static LedColor LED_ORANGE= new LedColor(0x300e0000);  // orange
    public final static LedColor LED_OFF   = new LedColor(0x00000000);
    
    // colors RGB. Version for photos
    public final static LedColor LED_REAL_RED = new LedColor(0x19000000);
    public final static LedColor LED_REAL_GREEN = new LedColor(0x00190000);  // orange
    public final static LedColor LED_REAL_BLUE = new LedColor(0x00001900);
    
    private final static int robotIDbitHeader = 0x80;
    private final static int robotIDbitMask = 0x7F;
    
    private final static int messageTypeMask = 0xF0;
    private final static int messageContentMask = 0x0F;
    private final static int response = 0x10;
    private final static int actionHeader = 0x20;
    private final static int feedbackHeader = 0x70;
    
    private final static int positiveFeedback = 0x0F;
    private final static int negativeFeedback = 0x00;
    

    // TAM this controller is attached two
    private TAMInterface tam;
    
    private int sequenceNumber;
    
    private int feedback;
    
    private int RobotID;

    private enum GiveFeedbackState {
        RECEIVE_ROBOT_ID, RECEIVE_ACTION, GIVE_FEEDBACK
    }

    private GiveFeedbackState giveFeedbackState;


    /**
     * Sets up the controller.
     * @param randomSeed   seed for the prng
     * @param tam          TAM this controller should be attached to
     */
    public void init(long randomSeed, TAMInterface tam, int sequenceNumber)
    {
        super.init(randomSeed);
        this.tam = tam;
        this.sequenceNumber = sequenceNumber;
        this.giveFeedbackState = GiveFeedbackState.RECEIVE_ROBOT_ID;
        this.RobotID = 0;
        this.feedback = negativeFeedback;
        log.info("New TAM controller");
    }

    /**
     * Step function of the controller. Called every Coordinator.STEP_TAMS_INTERVAL milliseconds.
     */
    public void step()
    {
    	if(tam.isRobotPresent()){ // if there is a robot in the tam
        	// Switch LEDs off
        	//log.info("Robot inside");
            tam.setLedColor(LED_OFF);
        	switch(giveFeedbackState){
	        	case RECEIVE_ROBOT_ID:{
	        		log.info("State: receive robot ID");
	        		log.info("Robot data: " + tam.getRobotDataReceived());
	        		if ((tam.getRobotDataReceived() & robotIDbitHeader) == robotIDbitHeader){
	        			log.info("ID from robot: " + (tam.getRobotDataReceived() & robotIDbitMask));
	        			RobotID = (tam.getRobotDataReceived() & robotIDbitMask);
	        			log.info("Going to RECEIVE_ACTION state");
	        			giveFeedbackState = GiveFeedbackState.RECEIVE_ACTION;
	        		}
	        	}
	        	break;
	        	case RECEIVE_ACTION:{
	        		log.info("State: receive action");
                    tam.setRobotDataToSend(response);
	        		if ((tam.getRobotDataReceived() & messageTypeMask) == actionHeader){
	        			log.info("Tam " + sequenceNumber + " Action from robot: " + (tam.getRobotDataReceived() & messageContentMask));
	        			if((tam.getRobotDataReceived() & messageContentMask) == sequenceNumber){
	        				log.info("Right action. Go to GIVE positive FEEDBACK");
	        				feedback = positiveFeedback;
	        				giveFeedbackState = GiveFeedbackState.GIVE_FEEDBACK;
	        			} else {
	        				log.info("Wrong action. Go to GIVE negative FEEDBACK");
	        				feedback = negativeFeedback;
	        				giveFeedbackState = GiveFeedbackState.GIVE_FEEDBACK;
	        			}
	        		}
	        	}
	        	break;
	        	case GIVE_FEEDBACK:{
	        		log.info("State: give feedback");
                    tam.setRobotDataToSend(feedbackHeader | feedback);
	        	}
	        	break;
        	}
        } else {
			feedback = negativeFeedback;
			this.RobotID = 0;
			if(giveFeedbackState != GiveFeedbackState.RECEIVE_ROBOT_ID ){
				log.info("Robot left the tam. Re-initialize state machine");
				giveFeedbackState = GiveFeedbackState.RECEIVE_ROBOT_ID;
			}
			
		}
    	switch(sequenceNumber){
	    	case 1:{
	    		if(!tam.isRobotPresent()){
                    tam.setLedColor(LED_RED);
		        }
	    	}
	    	break;
	    	case 2:{
	    		if(!tam.isRobotPresent()){
                    tam.setLedColor(LED_GREEN);
		        }
	    	}
	    	break;
	    	case 3:{
	    		if(!tam.isRobotPresent()){
                    tam.setLedColor(LED_BLUE);
		        }
	    	}
	    	break;
	    	case 4: {
	    		if(!tam.isRobotPresent()){
                    tam.setLedColor(LED_TEST);
		        }
	    	}
	    	break;
    	}
    }

}