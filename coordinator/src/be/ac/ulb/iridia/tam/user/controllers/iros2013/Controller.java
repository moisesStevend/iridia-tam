package be.ac.ulb.iridia.tam.user.controllers.iros2013;

import be.ac.ulb.iridia.tam.common.coordinator.Coordinator;
import be.ac.ulb.iridia.tam.common.tam.ControllerInterface;
import be.ac.ulb.iridia.tam.common.tam.LedColor;
import be.ac.ulb.iridia.tam.common.tam.TAM;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.TimerTask;


/**
 * This controller maps the behavior of a TAM to an abstract task.
 * All the task-related logic comes from the task and its (complex)
 * dependencies with other tasks.
 *
 * The TAM can take 3 colors: GREEN if free, RED if busy (robot is
 * working) and BLUE if the robot needs to wait).
 */
public class Controller implements ControllerInterface
{
    private final static Logger log = Logger.getLogger(Controller.class);

    // duration of the task, in milliseconds
    public static final long WORKING_DURATION = 10 * 1000;
    // dead time during which the TAM is switched off after the robot left, in milliseconds
    public static final long DEAD_TIME_DURATION = 5 * 1000;

    // colors of the RGB leds used (0x19 max value to keep eyes safe)
    public final static LedColor LED_C_AVAILABLE = new LedColor(0x00190000);  // green
    public final static LedColor LED_S_AVAILABLE = new LedColor(0x00001900);  // blue
    public final static LedColor LED_WORKING     = new LedColor(0x19000000);  // red
    public final static LedColor LED_WAITING     = new LedColor(0x190e0000);  // orange
    public final static LedColor LED_NO_TASK     = new LedColor(0x00000000);  // off/black

    // coordinator
    private Coordinator coordinator;
    // TAM this controller is attached to
    private TAM tamC1;
    private TAM tamC2;
    private TAM tamS;
    // current task duration timer (we need the reference to cancel the timer when the robot aborts the task)
    private TimerTask taskDurationTimerC1;
    private TimerTask taskDurationTimerC2;
    private TimerTask taskDurationTimerS;
    // timer for switching the TAM of for a certain amount of time after completion of task
    private TimerTask deadTimeTimer;

    private LedColor tamC1color;
    private LedColor tamC2color;
    private LedColor tamScolor;



    // possible states of task, no others are possible
    public enum State
    {
        IDLE,
        C_WAIT_ARRIVE,
        C1_WAIT_ARRIVE,
        C2_WAIT_ARRIVE,
        C_WORKING,
        S_WAIT_ARRIVE,
        C_WAIT_LEAVE,
        S_WORKING,
        S_WAIT_LEAVE,
        SUCCESS,
        FAIL
    }
    // current state of task
    State state;

    // mapping between states and transitions
    protected HashMap<String, Transition> transitions;
    protected HashMap<State, ArrayList<Transition>> outgoingTransitions;
    protected HashMap<State, ArrayList<Transition>> incomingTransitions;


    /**
     * Sets up the controller.
     * @param coordinator  coordinator that handles the networking
     * @param tamC1        concurrent task 1
     * @param tamC2        concurrent task 2
     * @param tamS        sequential task 1
     */
    public Controller(Coordinator coordinator, final TAM tamC1, final TAM tamC2, final TAM tamS)
    {
        this.coordinator = coordinator;
        this.tamC1 = tamC1;
        this.tamC2 = tamC2;
        this.tamS = tamS;

        state = State.IDLE;

        taskDurationTimerC1 = null;
        taskDurationTimerC2 = null;
        taskDurationTimerS = null;
        deadTimeTimer = null;
        tamC1color = LED_NO_TASK;
        tamC2color = LED_NO_TASK;
        tamScolor = LED_NO_TASK;

        // transition maps
        transitions = new HashMap<String, Transition>();
        outgoingTransitions = new HashMap<State, ArrayList<Transition>>();
        incomingTransitions = new HashMap<State, ArrayList<Transition>>();
        for (State state : State.values())
        {
            outgoingTransitions.put(state, new ArrayList<Transition>());
            incomingTransitions.put(state, new ArrayList<Transition>());
        }

        // all standard transitions of a task are just a linear chain
        addNewTransition(State.IDLE,           State.C_WAIT_ARRIVE);
        addNewTransition(State.C_WAIT_ARRIVE,  State.C1_WAIT_ARRIVE);
        addNewTransition(State.C_WAIT_ARRIVE,  State.C2_WAIT_ARRIVE);
        addNewTransition(State.C1_WAIT_ARRIVE, State.C_WORKING);
        addNewTransition(State.C2_WAIT_ARRIVE, State.C_WORKING);
        addNewTransition(State.C_WORKING,      State.S_WAIT_ARRIVE);
        addNewTransition(State.S_WAIT_ARRIVE,  State.C_WAIT_LEAVE);
        addNewTransition(State.C_WAIT_LEAVE,   State.S_WORKING);
        addNewTransition(State.S_WORKING,      State.S_WAIT_LEAVE);
        addNewTransition(State.S_WAIT_LEAVE,   State.SUCCESS);

        // FAIL transitions never fall through if there are no conditions
        // if there are multiple conditions, only one has to be true to trigger the transition
        addNewTransition(State.C1_WAIT_ARRIVE, State.FAIL, Transition.Type.OR, false);
        addNewTransition(State.C2_WAIT_ARRIVE, State.FAIL, Transition.Type.OR, false);
        addNewTransition(State.C_WORKING,      State.FAIL, Transition.Type.OR, false);
        addNewTransition(State.S_WAIT_ARRIVE,  State.FAIL, Transition.Type.OR, false);
        addNewTransition(State.C_WAIT_LEAVE,   State.FAIL, Transition.Type.OR, false);
        addNewTransition(State.S_WORKING,      State.FAIL, Transition.Type.OR, false);

        // end conditions: success of fail
        addNewTransition(State.SUCCESS, State.IDLE);
        addNewTransition(State.FAIL,    State.IDLE);

        /*
         * State IDLE
         */
        getTransition(State.IDLE, State.C_WAIT_ARRIVE).addCondition(new TransitionCondition()
        {
            public boolean evaluate(Transition transition)
            {
                return (deadTimeTimer == null);
            }

            public void reset(Transition transition) {}
        });
        getTransition(State.IDLE, State.C_WAIT_ARRIVE).addAction(new TransitionAction()
        {
            public void execute(Transition transition)
            {
                log.info(tamC1.getId() + ": Announcing task");
                log.info(tamC2.getId() + ": Announcing task");
                tamC1color = LED_C_AVAILABLE;
                tamC2color = LED_C_AVAILABLE;
            }
        });

        /*
         * State C_WAIT_ARRIVE
         */
        // we make the transition to C1_WAIT_ARRIVE only if a robot is present in C2
        getTransition(State.C_WAIT_ARRIVE, State.C1_WAIT_ARRIVE).addCondition(new TransitionCondition()
        {
            public boolean evaluate(Transition transition)
            {
                return tamC2.isRobotPresent();
            }
            public void reset(Transition transition) {}
        });
        getTransition(State.C_WAIT_ARRIVE, State.C1_WAIT_ARRIVE).addAction(new TransitionAction()
        {
            public void execute(Transition transition)
            {
                log.info(tamC2.getId() + ": Robot arrives and starts to wait for robot in " + tamC1.getId());
                tamC2color = LED_WAITING;
            }
        });
        // we make the transition to C2_WAIT_ARRIVE only if a robot is present in C1
        getTransition(State.C_WAIT_ARRIVE, State.C2_WAIT_ARRIVE).addCondition(new TransitionCondition()
        {
            public boolean evaluate(Transition transition)
            {
                return tamC1.isRobotPresent();
            }
            public void reset(Transition transition) {}
        });
        getTransition(State.C_WAIT_ARRIVE, State.C2_WAIT_ARRIVE).addAction(new TransitionAction()
        {
            public void execute(Transition transition)
            {
                log.info(tamC1.getId() + ": Robot arrives and starts to wait for robot in " + tamC2.getId());
                tamC1color = LED_WAITING;
            }
        });

        /*
         * State CX_WAIT_ARRIVE
         */
        TransitionAction setupWorkTimersActionC = new TransitionAction()
        {
            public void execute(Transition transition)
            {
                // we set the taskDurationTimer so that we can cancel the timer when the task was aborted
                taskDurationTimerC1 = new TimerTask()
                {
                    @Override
                    public void run()
                    {
                        log.info(tamC1.getId() + ": Task working time is over");
                        tamC1color = LED_WAITING;
                        taskDurationTimerC1 = null;
                    }
                };
                getCoordinator().getTimer().schedule(taskDurationTimerC1, WORKING_DURATION);
                // we set the taskDurationTimer so that we can cancel the timer when the task was aborted
                taskDurationTimerC2 = new TimerTask()
                {
                    @Override
                    public void run()
                    {
                        log.info(tamC2.getId() + ": Task working time is over");
                        tamC2color = LED_WAITING;
                        taskDurationTimerC2 = null;
                    }
                };
                getCoordinator().getTimer().schedule(taskDurationTimerC2, WORKING_DURATION);
            }
        };

        /*
         * State C1_WAIT_ARRIVE
         */
        // we make the transition to WORKING if the robot is still present
        getTransition(State.C1_WAIT_ARRIVE, State.C_WORKING).addCondition(new TransitionCondition()
        {
            public boolean evaluate(Transition transition)
            {
                return tamC1.isRobotPresent();
            }
            public void reset(Transition transition) {}
        });
        getTransition(State.C1_WAIT_ARRIVE, State.C_WORKING).addAction(new TransitionAction()
        {
            public void execute(Transition transition)
            {
                log.info(tamC1.getId() + ": Robot arrives, starts to work on subtask C1");
                log.info(tamC2.getId() + ": Robot stops waiting, starts to work on subtask C2");
                log.info(tamC1.getId() + ": Subtask C1 started");
                log.info(tamC2.getId() + ": Subtask C2 started");
                tamC1color = LED_WORKING;
                tamC2color = LED_WORKING;
            }
        });
        getTransition(State.C1_WAIT_ARRIVE, State.C_WORKING).addAction(setupWorkTimersActionC);
        // we make the transition to FAIL if the robot left
        getTransition(State.C1_WAIT_ARRIVE, State.FAIL).addCondition(new TransitionCondition()
        {
            public boolean evaluate(Transition transition)
            {
                return !tamC2.isRobotPresent();
            }
            public void reset(Transition transition) {}
        });
        getTransition(State.C1_WAIT_ARRIVE, State.FAIL).addAction(new TransitionAction()
        {
            public void execute(Transition transition)
            {
                log.info(tamC2.getId() + ": Robot aborted task during waiting for partner");
                tamC1color = LED_NO_TASK;
                tamC2color = LED_NO_TASK;
                tamScolor = LED_NO_TASK;
            }
        });

        /*
         * State C2_WAIT_ARRIVE
         */
        // we make the transition to WORKING if the robot is still present
        getTransition(State.C2_WAIT_ARRIVE, State.C_WORKING).addCondition(new TransitionCondition()
        {
            public boolean evaluate(Transition transition)
            {
                return tamC2.isRobotPresent();
            }
            public void reset(Transition transition) {}
        });
        getTransition(State.C2_WAIT_ARRIVE, State.C_WORKING).addAction(new TransitionAction()
        {
            public void execute(Transition transition)
            {
                log.info(tamC2.getId() + ": Robot arrives, starts to work on subtask C1");
                log.info(tamC1.getId() + ": Robot stops waiting, starts to work on subtask C2");
                log.info(tamC1.getId() + ": Subtask C1 started");
                log.info(tamC2.getId() + ": Subtask C2 started");
                tamC1color = LED_WORKING;
                tamC2color = LED_WORKING;
            }
        });
        getTransition(State.C2_WAIT_ARRIVE, State.C_WORKING).addAction(setupWorkTimersActionC);
        // we make the transition to FAIL if the robot left
        getTransition(State.C2_WAIT_ARRIVE, State.FAIL).addCondition(new TransitionCondition()
        {
            public boolean evaluate(Transition transition)
            {
                return !tamC1.isRobotPresent();
            }
            public void reset(Transition transition) {}
        });
        getTransition(State.C2_WAIT_ARRIVE, State.FAIL).addAction(new TransitionAction()
        {
            public void execute(Transition transition)
            {
                log.info(tamC1.getId() + ": Robot aborted task during waiting for partner");
                tamC1color = LED_NO_TASK;
                tamC2color = LED_NO_TASK;
                tamScolor = LED_NO_TASK;
            }
        });

        /*
         * State C_WORKING
         */
        // we make the transition to WAIT_AFTER if the robot is still present
        getTransition(State.C_WORKING, State.S_WAIT_ARRIVE).addCondition(new TransitionCondition()
        {
            public boolean evaluate(Transition transition)
            {
                return tamC1.isRobotPresent() && taskDurationTimerC1 == null && tamC2.isRobotPresent() && taskDurationTimerC2 == null;
            }
            public void reset(Transition transition) {}
        });
        getTransition(State.C_WORKING, State.S_WAIT_ARRIVE).addAction(new TransitionAction()
        {
            public void execute(Transition transition)
            {
                log.info(tamC1.getId() + ": Subtask C1 done");
                log.info(tamC2.getId() + ": Subtask C2 Done");
                log.info(tamS.getId() + ": Available, waiting for robot to arrive.");

                tamC1color = LED_WAITING;
                tamC2color = LED_WAITING;
                tamScolor = LED_S_AVAILABLE;
            }
        });
        // we make the transition to FAIL if either robot left
        getTransition(State.C_WORKING, State.FAIL).addCondition(new TransitionCondition()
        {
            public boolean evaluate(Transition transition)
            {
                return !tamC1.isRobotPresent() || !tamC2.isRobotPresent();
            }
            public void reset(Transition transition) {}
        });
        getTransition(State.C_WORKING, State.FAIL).addAction(new TransitionAction()
        {
            public void execute(Transition transition)
            {
                if (!tamC1.isRobotPresent())
                    log.info(tamC1.getId() + ": Robot aborted subtask C1 during working");
                if (!tamC2.isRobotPresent())
                    log.info(tamC2.getId() + ": Robot aborted subtask C2 during working");
                if (taskDurationTimerC1 != null)
                {
                    taskDurationTimerC1.cancel();
                    taskDurationTimerC1 = null;
                }
                if (taskDurationTimerC2 != null)
                {
                    taskDurationTimerC2.cancel();
                    taskDurationTimerC2 = null;
                }
                tamC1color = LED_NO_TASK;
                tamC2color = LED_NO_TASK;
                tamScolor = LED_NO_TASK;
            }
        });

        /*
         * State S_WAIT_ARRIVE
         */
        // we make the transition to WORKING if the robot is still present
        getTransition(State.S_WAIT_ARRIVE, State.C_WAIT_LEAVE).addCondition(new TransitionCondition()
        {
            public boolean evaluate(Transition transition)
            {
                return tamS.isRobotPresent();
            }
            public void reset(Transition transition) {}
        });
        getTransition(State.S_WAIT_ARRIVE, State.C_WAIT_LEAVE).addAction(new TransitionAction()
        {
            public void execute(Transition transition)
            {
                log.info(tamC1.getId() + ": Robot has to leave");
                log.info(tamC2.getId() + ": Robot has to leave");
                log.info(tamS.getId() + ": Waiting for other robots to leave");

                tamC1color = LED_NO_TASK;
                tamC2color = LED_NO_TASK;
                tamScolor = LED_WAITING;
            }
        });
        // we make the transition to FAIL if either robot left
        getTransition(State.S_WAIT_ARRIVE, State.FAIL).addCondition(new TransitionCondition()
        {
            public boolean evaluate(Transition transition)
            {
                return !tamC1.isRobotPresent() || !tamC2.isRobotPresent();
            }
            public void reset(Transition transition) {}
        });
        getTransition(State.S_WAIT_ARRIVE, State.FAIL).addAction(new TransitionAction()
        {
            public void execute(Transition transition)
            {
                if (!tamC1.isRobotPresent())
                    log.info(tamC1.getId() + ": Robot aborted subtask C1 during waiting for sequential task");
                if (!tamC2.isRobotPresent())
                    log.info(tamC2.getId() + ": Robot aborted subtask C2 during waiting for sequential task");
                tamC1color = LED_NO_TASK;
                tamC2color = LED_NO_TASK;
                tamScolor = LED_NO_TASK;
            }
        });

        /*
         * State C_WAIT_LEAVE
         */
        // we make the transition to WORKING if the robot is still present
        getTransition(State.C_WAIT_LEAVE, State.S_WORKING).addCondition(new TransitionCondition()
        {
            public boolean evaluate(Transition transition)
            {
                return !tamC1.isRobotPresent() && !tamC2.isRobotPresent();
            }
            public void reset(Transition transition) {}
        });
        getTransition(State.C_WAIT_LEAVE, State.S_WORKING).addAction(new TransitionAction()
        {
            public void execute(Transition transition)
            {
                log.info(tamC1.getId() + ": Robot left");
                log.info(tamC2.getId() + ": Robot left");
                log.info(tamS.getId() + ": Subtask S started");

                tamScolor = LED_WORKING;

                // we set the taskDurationTimer so that we can cancel the timer when the task was aborted
                taskDurationTimerS = new TimerTask()
                {
                    @Override
                    public void run()
                    {
                        log.info(tamS.getId() + ": Subtask S working time is over");
                        taskDurationTimerS = null;
                    }
                };
                getCoordinator().getTimer().schedule(taskDurationTimerS, WORKING_DURATION);
            }
        });
        // we make the transition to FAIL if sequential robot left
        getTransition(State.C_WAIT_LEAVE, State.FAIL).addCondition(new TransitionCondition()
        {
            public boolean evaluate(Transition transition)
            {
                return !tamS.isRobotPresent();
            }
            public void reset(Transition transition) {}
        });
        getTransition(State.C_WAIT_LEAVE, State.FAIL).addAction(new TransitionAction()
        {
            public void execute(Transition transition)
            {
                log.info(tamS.getId() + ": Robot aborted subtask S during waiting");
                tamC1color = LED_NO_TASK;
                tamC2color = LED_NO_TASK;
                tamScolor = LED_NO_TASK;
            }
        });

        /*
         * State S_WORKING
         */
        // we make the transition to WORKING if the robot is still present
        getTransition(State.S_WORKING, State.S_WAIT_LEAVE).addCondition(new TransitionCondition()
        {
            public boolean evaluate(Transition transition)
            {
                return tamS.isRobotPresent() && taskDurationTimerS == null;
            }
            public void reset(Transition transition) {}
        });
        getTransition(State.S_WORKING, State.S_WAIT_LEAVE).addAction(new TransitionAction()
        {
            public void execute(Transition transition)
            {
                log.info(tamS.getId() + ": Subtask S done");
                log.info(tamS.getId() + ": Robot has to leave");
                tamScolor = LED_NO_TASK;
            }
        });
        // we make the transition to FAIL if sequential robot left
        getTransition(State.S_WORKING, State.FAIL).addCondition(new TransitionCondition()
        {
            public boolean evaluate(Transition transition)
            {
                return !tamS.isRobotPresent();
            }
            public void reset(Transition transition) {}
        });
        getTransition(State.S_WORKING, State.FAIL).addAction(new TransitionAction()
        {
            public void execute(Transition transition)
            {
                log.info(tamS.getId() + ": Robot aborted subtask S during working");
                if (taskDurationTimerS != null)
                {
                    taskDurationTimerS.cancel();
                    taskDurationTimerS = null;
                }
                tamC1color = LED_NO_TASK;
                tamC2color = LED_NO_TASK;
                tamScolor = LED_NO_TASK;
            }
        });

        /*
         * State S_WAIT_LEAVE
         */
        // we make the transition to WORKING if the robot is still present
        getTransition(State.S_WAIT_LEAVE, State.SUCCESS).addCondition(new TransitionCondition()
        {
            public boolean evaluate(Transition transition)
            {
                return !tamS.isRobotPresent();
            }
            public void reset(Transition transition) {}
        });
        getTransition(State.S_WAIT_LEAVE, State.SUCCESS).addAction(new TransitionAction()
        {
            public void execute(Transition transition)
            {
                log.info(tamS.getId() + ": Robot left");
            }
        });

        /*
         * State SUCCESS
         */
        // we make the transition to IDLE if the robot left
        getTransition(State.SUCCESS, State.IDLE).addCondition(new TransitionCondition()
        {
            public boolean evaluate(Transition transition)
            {
                return !tamC1.isRobotPresent() && !tamC2.isRobotPresent() && !tamS.isRobotPresent() && deadTimeTimer == null;
            }
            public void reset(Transition transition) {}
        });
        getTransition(State.SUCCESS, State.IDLE).addAction(new TransitionAction()
        {
            public void execute(Transition transition)
            {
                log.info("Task SUCCEEDED!");

                deadTimeTimer = new TimerTask()
                {
                    @Override
                    public void run()
                    {
                        log.info("Dead time is over");
                        deadTimeTimer = null;
                    }
                };
                getCoordinator().getTimer().schedule(deadTimeTimer, DEAD_TIME_DURATION);

                tamC1color = LED_NO_TASK;
                tamC2color = LED_NO_TASK;
                tamScolor = LED_NO_TASK;
            }
        });

        /*
         * State FAIL
         */
        // we make the transition to IDLE if the robot left
        getTransition(State.FAIL, State.IDLE).addCondition(new TransitionCondition()
        {
            public boolean evaluate(Transition transition)
            {
                return !tamC1.isRobotPresent() && !tamC2.isRobotPresent() && !tamS.isRobotPresent();
            }
            public void reset(Transition transition) {}
        });
        getTransition(State.FAIL, State.IDLE).addAction(new TransitionAction()
        {
            public void execute(Transition transition)
            {
                log.info("Task FAILED!");
                tamC1color = LED_NO_TASK;
                tamC2color = LED_NO_TASK;
                tamScolor = LED_NO_TASK;
            }
        });

        log.info("New TAM controller, starting in state "+ getState());
    }

    private void setLeds(TAM tam, LedColor color)
    {
        if (tam.getLedColorLastUpdated() == 0 || !tam.getLedColor().equals(color))
        {
            getCoordinator().sendSetLedsCommand(tam, color);
        }
    }


    /**
     * Step function of the controller. Called every Coordinator.STEP_TAMS_INTERVAL milliseconds.
     */
    public void step()
    {
        // step the task fsm that check conditions and switches state
        // if we changed state, execute again to see if we can fast-switch through multiple states
        while (checkAndExecuteTransitions()) {}

//        log.debug("============== Stepping "+tam.getId()+" task state "+getState()+ " ================");

        setLeds(tamC1, tamC1color);
        setLeds(tamC2, tamC2color);
        setLeds(tamS, tamScolor);

//        log.debug("==============================");
    }

    /**
     * Returns the associated coordinator.
     * @return coordinator that runs the show
     */
    public Coordinator getCoordinator()
    {
        return coordinator;
    }

    /**
     * Adds a new transition from a state to another. This is a helper method
     * that creates a new transition object and adds it to the appropriate maps.
     * @param fromState    transition from this state
     * @param toState      transition to this state
     * @param type         Transition.Type.AND if all conditions must be true to switch, Transition.Typ.OR if one is sufficient
     * @param fallThrough  set to true if the transition switches if no condition is set
     * @return new transition object
     */
    public Transition addNewTransition(State fromState, State toState, Transition.Type type, boolean fallThrough)
    {
        Transition transition = new Transition(this, fromState, toState, type, fallThrough);

        transitions.put(computeTransitionKey(fromState, toState), transition);
        incomingTransitions.get(toState).add(transition);
        outgoingTransitions.get(fromState).add(transition);

        return transition;
    }

    /**
     * Adds a new transition from a state to another. This is a helper method
     * that creates a new transition object and adds it to the appropriate maps.
     * The type will be set to Transition.Type.AND.
     * @param fromState    transition from this state
     * @param toState      transition to this state
     * @param fallThrough  set to true if the transition switches if no condition is set
     * @return new transition object
     */
    @SuppressWarnings("unused")
    public Transition addNewTransition(State fromState, State toState, boolean fallThrough)
    {
        return addNewTransition(fromState, toState, Transition.Type.AND, fallThrough);
    }

    /**
     * Adds a new transition from a state to another. This is a helper method
     * that creates a new transition object and adds it to the appropriate maps.
     * The type will be set to Transition.Type.AND and fall-through will be true.
     * @param fromState    transition from this state
     * @param toState      transition to this state
     * @return new transition object
     */
    public Transition addNewTransition(State fromState, State toState)
    {
        return addNewTransition(fromState, toState, Transition.Type.AND, true);
    }

    /**
     * Returns a transition between two states.
     * @param fromState  transition from this state
     * @param toState    transition to this state
     * @return transition, or null if does not exist
     */
    public Transition getTransition(State fromState, State toState)
    {
        return transitions.get(computeTransitionKey(fromState, toState));
    }

    /**
     * Returns the incoming transitions of a state.
     * @param toState  all transitions to this state
     * @return list of transitions (can be empty)
     */
    @SuppressWarnings("unused")
    public ArrayList<Transition> getIncomingTransitions(State toState)
    {
        return incomingTransitions.get(toState);
    }

    /**
     * Returns the outgoing transitions of a state.
     * @param fromState  all transitions from this state
     * @return list of transitions (can be empty)
     */
    @SuppressWarnings("unused")
    public ArrayList<Transition> getOutgoingTransitions(State fromState)
    {
        return outgoingTransitions.get(fromState);
    }

    /**
     * Returns the current state of this task.
     * @return current state as AbstractTask.State
     */
    public State getState()
    {
        return state;
    }

    /**
     * Sets a new state of this task.
     * @param newState  new state to set
     */
    public void setState(State newState)
    {
        if (newState != state)
        {
            log.debug(this + " state changed from " + state + " to " + newState);
            state = newState;
        }
    }

    /**
     * Checks and executes outgoing transitions of the current state.
     * First transition that will be found to be executable (whose
     * conditions are met) will be executed. This changes the current
     * state of the task.
     * @return true if state changed
     */
    private boolean checkAndExecuteTransitions()
    {
        // see if we can change the state
        for (Transition transition : outgoingTransitions.get(state))
        {
            if (transition.execute())
            {
                setState(transition.getToState());
                return true;
            }
        }
        return false;
    }

    /**
     * Computes the key for storing a transition in the map.
     * @param fromState  transition from this state
     * @param toState    transition to this state
     * @return key as string
     */
    private String computeTransitionKey(State fromState, State toState)
    {
        return fromState.toString()+">"+toState.toString();
    }

    /**
     * Returns a String representation of this task.
     * @return String representation of this task
     */
    @Override
    public String toString()
    {
        return "Controller{state=" + getState() + '}';
    }
}