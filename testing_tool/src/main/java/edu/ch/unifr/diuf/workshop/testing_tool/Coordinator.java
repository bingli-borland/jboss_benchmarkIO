package edu.ch.unifr.diuf.workshop.testing_tool;

import net.schmizz.sshj.transport.TransportException;
import org.apache.commons.configuration.ConfigurationException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This object is supposed to control everything it is running.
 *
 * @author Teodor Macicas
 */
public class Coordinator 
{   
    private final static Logger LOGGER = Logger.getLogger(
            Coordinator.class.getName());
    
    public static void main(String... args) {
        MachineManager mm = new MachineManager();
        
        try {
            System.out.println("Parsing properties file ...");
            mm.parsePropertiesFile();
        }
        catch( WrongIpAddressException|WrongPortNumberException|ClientNotProperlyInitException ex ) {
            LOGGER.log(Level.SEVERE, "[EXCEPTION] Setting up a machine.", ex);
            System.exit(1);
        }
        catch( ConfigurationException ex4 ) { 
            LOGGER.log(Level.SEVERE, "[EXCEPTION] Reading the properties file.", ex4);
            System.exit(4);
        }
        catch( FileNotFoundException ex5 ) {
            LOGGER.log(Level.SEVERE, "[EXCEPTION] Either server local program or the client"
                    + " local program could not be found.", ex5);
            System.exit(5);
        }
        // print the machines that have been created according to the properties file
        System.out.println(mm.printMachines());
        
        // are both server and clients set up?
        if( ! mm.checkIfClientAndServerSet() ) { 
            LOGGER.severe("[ERROR] Either clients or the server is not yet configured. "
                    + "Please do so before you start once again.");
            System.exit(6);
        }
        // are either all or none loopback addresses used?
        if( ! mm.checkIfAllOrNoneLoopbackAddresses() ) { 
            LOGGER.severe("[ERROR] Please either use loopback addresses for all clients "
                    + "and server OR non-loopback for all machines. This will be "
                    + "more probably they can reach other.");
            System.exit(7);
        }
        
        System.out.println("[INFO] Checking if all clients can ping the server ...");
        try {
            // can all clients, at least, ping the server?
            if( ! mm.checkClientsCanAccessServer() ) {
                LOGGER.severe("[ERROR] Not all clients can ping the server. Check once again "
                        + "the IP addresses and/or the network status.");
                System.exit(8);
            }
        } catch (TransportException ex) {
            LOGGER.log(Level.SEVERE, "[EXCEPTION] catched while checking clients "
                    + "network connection to the server.", ex);
            System.exit(8);
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, "[EXCEPTION] catched while checking clients "
                    + "network connection to the server.", ex);
            System.exit(8);
        }
        System.out.println("[INFO] All clients have network connection with server.");

        // delete also local .data files
        try {
            Runtime.getRuntime().exec("/bin/bash rm log*.data").waitFor();
        } catch (IOException|InterruptedException ex) {
            Logger.getLogger(Coordinator.class.getName()).log(Level.SEVERE, null, ex);
        }

        // upload the programs to clients and to the server
        try {
            System.out.println("[INFO] Start uploading the program to clients ...");
            mm.uploadProgramToClients();
            System.out.println("[INFO] Start uploading the program to server ...");
            mm.uploadProgramToServer();
        } catch (TransportException ex) {
                LOGGER.log(Level.SEVERE, null, ex);
                System.exit(9);
        } catch (IOException ex) {
                LOGGER.log(Level.SEVERE, null, ex);
                System.exit(10);
        }
        
        // now start the connectivity and status threads
        mm.startConnectivityThread();

        System.out.println("[INFO] Checking if all machines are in a runnable state ...");
        try {
            // check if all are ok ...
            // sleep a bit before checking, to allow some time for the coordinator
            // to contact each client
            Thread.sleep(2000);
            int retries = 10;
            while( ! mm.allAreConnectionsOK() && retries > 0 ) {
                --retries;
                LOGGER.info(" There are some machines that have either ssh problems "
                    + "or just connectivity problems. Wait and retry (left "
                    + "#retries: " + retries + ").");
                Thread.sleep(10000);
            }
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
        System.out.println("[INFO] ALL machines checked and they are in a runnable state.");

        System.out.println("[INFO] Starting the threads for checking connectivity, status and running PIDs ...");
        // now start all the other thread as the connectity at this point should be ok
        mm.startOtherThreads();

        //get tests to run
        List<String> tests = mm.getClientNo(0).getTests();
        Coordinator coordinator = new Coordinator();
        for(int i = 0; i < tests.size(); i++) {
            try {
                Method method = coordinator.getClass().getDeclaredMethod(tests.get(i), MachineManager.class);
                method.invoke(coordinator, mm);
                
            } catch (NoSuchMethodException|InvocationTargetException|IllegalAccessException e) {
                LOGGER.log(Level.SEVERE, "Test method invocation error.", e);
                System.exit(11);
            }
        }
        mm.joinAllThreads();
    }

    private void defaultTest(MachineManager mm) {
        System.out.println("[INFO] Run test with config parameters");
        runClients(mm, "defaultTest");
    }

    private void delayTest(MachineManager mm) {
        System.out.println("[INFO] Run tests with different delays");
        int[] delays = {250, 225, 200, 175, 150, 125, 100};
        for(int i = 0; i < delays.length; i++) {
            for(int j = 0; j < mm.getClientsNum(); j++) {
                mm.getClientNo(j).setDelay(delays[i]);
            }
            runClients(mm, "defaultTest");
        }
        // now join the helper threads
        mm.joinAllThreads();
    }

    private void asyncServersTest(MachineManager mm) {
        System.out.println("[INFO] Run tests with different server types in async mode");
        String[] servers = {"xnio3", "nio2", "netty"};
        String mode = "async";
        for(int i = 0; i < servers.length; i++) {
            try {
                mm.getServer().setServerType(servers[i]);
                mm.getServer().setServerMode(mode);

                System.out.println("[INFO] Run tests with " + servers[i] + " server type");
                runClients(mm, Utils.getMethodName(0));
                /*if (i < servers.length - 1) {
                    mm.updateWorkingThreads();
                }*/
            } catch (WrongServerTypeException|WrongServerModeException e) {
                LOGGER.log(Level.SEVERE, "Wrong server type ot mode name.", e);
            }
        }
    }

    private void allTests(MachineManager mm) {
        System.out.println("[INFO] Run all tests");
        defaultTest(mm);
        delayTest(mm);
    }
    
    private void runClients(MachineManager mm, String testName) {
        RunClient rc = new RunClient(mm, mm.getServer().getRestartAttempts(), testName);
        rc.start();
        if( mm.getServer().getFaultTolerant().equals("yes") ) {
            mm.startFaultTolerantThread(rc);
        }
        try {
            rc.join();
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
        //get status 
        if( rc.status != 0 ) 
            LOGGER.log(Level.SEVERE, "Test " + testName + " could not be run. ");
        else
            LOGGER.log(Level.INFO, "Test " + testName + " was successfully run. ");
    }
    
    
    class RunClient extends Thread 
    {
        private MachineManager mm;
        private int no_retrials;
        private int status;
        private String testName;
        
        public RunClient(MachineManager mm, Integer retrials, String testName) {
            this.mm = mm;
            this.no_retrials = retrials;
            this.testName = testName;
        }
        
        public void run() {
            while( true ) {
                try {
                    if( no_retrials == 0 ) {
                        // this means all retrials have been tried, but without any success
                        status = 1;
                        break;
                    }
                    runClients(mm);
                    status = 0;
                    break;
                } catch (RerunTestException ex) {
                    --no_retrials;
                    LOGGER.log(Level.INFO, "Test " + ex.getMessage() + " could not be run "
                            + "due to too many failing clients. Retry ... " + no_retrials 
                            + " retrials remaining");
                }
            }
        }
        
        // start server and client program, synchronize, run test, fetch the logs
        // NOTE: this can be interrupted by the fault tolerant thread 
        private void runClients(MachineManager mm) throws RerunTestException {
            try {
                System.out.println("[INFO] Deleting any data from previous run ...");
                // delete from each client the files that might have been used before for
                // sending different messages
                mm.deleteClientPreviouslyMessages();
                
                System.out.println("[INFO] Start the server ... ");
                // run the server remotely
                mm.getServer().runServerRemotely();

                System.out.println("[INFO] Start the clients ...  ");
                mm.startAllClients();
                
                // check if all clients are synchronized
                while( true ) {
                    if( ! mm.checkClientsSynch() ) {
                        System.out.println("[INFO] Clients' threads are not yet synchronized. "
                                + "Wait more time ... ");
                        Thread.sleep(3000);
                    }
                    else
                        break;
                }
                System.out.println("[INFO] Clients' threads are synched. Now start the requests.");

                // send a message to the clients to start sending requests as they are now synchronized
                mm.sendClientsMsgToStartRequests();
                System.out.println("[INFO] All clients are now sending requests to the server.");

                // check if tests are completed
                while( true ) {
                    if( ! mm.checkTestsCompletion() ) {
                        System.out.println("[INFO] Client tests are not done yet ... wait more.");
                        Thread.sleep(5000);
                    }
                    else
                        break;
                }
                System.out.println("[INFO] Client tests are done.");

                System.out.println("[INFO] Now locally download the logs from the clients.");
                // get all logs to the server
                mm.downloadAllLogs();
                System.out.println("[INFO] All the logs are downloaded. For further information "
                        + "please check them.");
            } 
            catch (Exception ex) {
                if( (ex.getCause() != null && ex.getCause() instanceof InterruptedException) 
                      || ex instanceof InterruptedException ) {
                    // if the cause is InterruptedException, most probably, it has been thrown 
                    // by the fault tolerant thread
                    System.out.println("[FAULT TOLERANCE] The condition of dead clients "
                            + "has been reached. Restart the server and the clients for "
                            + "the test " + testName + " ... ");
                    throw new RerunTestException(testName);
                }
                else 
                    LOGGER.log(Level.SEVERE,"[EXCEPTION] ", ex);
            }
            finally {
                // even if success or failure, kill clients and server 
                try {
                    System.out.println("[INFO] Kill all the clients (if they are still running) ... ");
                    mm.killClients();
                    System.out.println("[INFO] Kill the server ... ");
                    mm.getServer().killServer();
                    // delete the data files also
                    SSHCommands.deleteRemoteFile(mm.getServer(), "data/");
                } catch (Exception ex) {
                    Logger.getLogger(Coordinator.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
    }
}

class RerunTestException extends Exception 
{
    public RerunTestException(String string) {
        super(string);
    }
}