import java.util.*;
import java.util.logging.*;
import java.io.*;
import java.net.*;
import java.nio.*;


public class Peer {
   
    //Peer info file data
    static int portNum = -1;
    static int isOwner = -1;

    //Config file data
    static String fileName = "";
    static int pieceSize = -1;
    static int floatPiece = -1;
    static double fileSize = -1;
    static int numPieces = -1;
   
    //Client List array, contains the list of peers
    static ArrayList<Socket> clientList = new ArrayList<Socket>();

    static FileHandler fileHandler;
    static File file;
    static Logger logger = Logger.getLogger("Log");
    static Random random= new Random();

    /**
     * HASHMAPS TO BE USED
     */
     //Hashmap for Message
     static HashMap<String, byte[]> messageTypeMap = createMessageHashMap();
     //  Hashmap for self and what pieces we have and which one we need
     static HashMap<Integer, byte[]> pieceMap = new HashMap<>();

     // Hashmap for Neighbors and what pieces they have and which one they need
     static HashMap<Integer, HashMap<Integer, Boolean>> neighborsPieceMap = new HashMap<Integer, HashMap<Integer, Boolean>>();
     //Neighbor Hashmap
     static HashMap<Integer, Socket> neighborMap = new HashMap<Integer, Socket>();
   
     // hashmap of the peers that are done
     static HashMap<Integer, Boolean> peersDone = new HashMap<>();

     // Hashmap for connection info
     static HashMap<Integer, String[]> peerInfoMap = new HashMap<Integer, String[]>();

      // hashmap for download rates
     static HashMap<Integer, Integer> downloadRates = new HashMap<>();  

    // Byte Array Output Stream for sending the byte arrays
    static ByteArrayOutputStream byteStream = new ByteArrayOutputStream();


    public static void main(String[] args) throws Exception {

        //Current peer ID
        int pID = Integer.valueOf(args[0]);

        //Initializing log for current peer
        file = new File(System.getProperty("user.dir") + "/" + pID);
        file.mkdir();
        String logFile = file.getAbsolutePath() + "/" + pID + " LOG.log";

        try {
            //adding handler for log file
            fileHandler = new FileHandler(logFile);
            logger.addHandler(fileHandler);
            logger.setUseParentHandlers(false);
            SimpleFormatter formatter = new SimpleFormatter();
            fileHandler.setFormatter(formatter);

        } catch (SecurityException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

         /**
         * Peer Information Retrievel
         */
        BufferedReader reader = new BufferedReader(new FileReader("LocalP.txt"));

        String read = reader.readLine();

        //Connection information of peers, reading line by line
        while (read != null) {
            String readLine[] = read.split(" ");
            int tempPeerID = Integer.valueOf(readLine[0]);
            String peerInfo[] = Arrays.copyOfRange(readLine, 1, 4);
            peerInfoMap.put(tempPeerID, peerInfo);
            read = reader.readLine();
        }
        reader.close();

        // Retrieve cofig.properties data
        try (InputStream input = new FileInputStream("config.properties")) {

            //Initializing properties file
            Properties prop = new Properties();
            prop.load(input);

            //retrieving properties
            fileName = prop.getProperty("FileName");
            pieceSize = Integer.valueOf(prop.getProperty("PieceSize"));
            fileSize = Integer.valueOf(prop.getProperty("FileSize"));
            numPieces = (int) Math.ceil(fileSize / pieceSize);
            System.out.println("Piece size: " + pieceSize);
            System.out.println("File size: " + fileSize);
            System.out.println("Num pieces: " + numPieces);

        } catch (IOException ex) {
            ex.printStackTrace();
        }       

        /**
         * Initializing Current Peer
         */        
        boolean peerConnected = false;
        boolean fileReady = false;
        String[] thisPeer = peerInfoMap.get(pID);
        portNum = Integer.valueOf(thisPeer[1]);
        isOwner = Integer.valueOf(thisPeer[2]);
        System.out.println("Current Peer " + pID + " Port Number: " + portNum + " Is Owner? " + isOwner);

        //Intitializing socket serverSocket for current peer
        ServerSocket serverSocket = new ServerSocket(portNum);

        //Initialise file read and peer connection
        try {
            while (true) {//Listen always
                if (isOwner == 1) {
                    if (!fileReady) {
                        
                        int i = 1;
                        byte[] pieceBuffer = new byte[pieceSize];
                        File file = new File(fileName);
                        System.out.println(file.length());

                        //Mapping file data
                        try (FileInputStream fileInputStream = new FileInputStream(file);
                                BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream)) {

                            int bytesRead = 0;

                            //Populate map with pieces
                            while ((bytesRead = bufferedInputStream.read(pieceBuffer)) > 0) {

                                //write pieces on output bytestream
                                byteStream.write(pieceBuffer, 0, bytesRead);
                                byte[] piece = byteStream.toByteArray();

                                //populate piece map
                                pieceMap.put(i, piece);
                                byteStream.flush();
                                byteStream.reset();
                                i++;

                            }
                            //ready after all the pieces are mapped 
                            fileReady = true;
                        }
                    }

                    //Add peer connection socket inorder to interact with all the peers
                    Socket peerSocket = serverSocket.accept();
                    clientList.add(peerSocket); 
                    
                    //Initialize Connection handler by passing peerSocket and current peerID
                    new Handler(peerSocket, pID).start();
                } 
                 else 
                  { //if current peer is not the owner
                    if (!peerConnected) {
                                               
                        for (int id : peerInfoMap.keySet()) {

                            //get the peerInfo for all the ids
                            String[] peerInfo = peerInfoMap.get(id);
                            //check if the peer is owner, if the owner value is 1
                            int peerIsOwner = Integer.valueOf(peerInfo[2]);

                            if (peerIsOwner == 1) {
                                //To not send the piesces to the owner
                                peersDone.put(pID, true);
                            } else {
                                peersDone.put(pID, false);
                            }

                            /**
                             * Connect in increasing order of peerID,i.e. 1001, then 1002, and so on
                             */
                            if (id < pID) {
                                //Host ID and host port from peerInfo
                                String hostId = peerInfo[0];
                                int hostPort = Integer.valueOf(peerInfo[1]);
                                
                                //Connection prompt
                                System.out.println("Connecting to:" + id + " At host: " + hostId + " Port: "
                                        + hostPort);
                                Socket requestSocket = new Socket(hostId, hostPort);

                                //New thread to connect to peer and put them into neighborMap
                                new Thread(new Client(hostPort, hostId, pID, requestSocket)).start();
                                neighborMap.put(id, requestSocket);
                            }

                        }
                        // connected to all available peers
                        peerConnected = true; 
                    }
                    Socket peerSocket = serverSocket.accept();
                    clientList.add(peerSocket); // will be used to communicate with all clients
                    new Thread(new Handler(peerSocket, pID)).start();

                }
            }
        } finally {
            //Close resources
            serverSocket.close();
            for (java.util.logging.Handler fileHandler : logger.getHandlers()) {
                fileHandler.close(); // must call fileHandler.close or a .LCK file will remain.
            }
        }
    }

    /**handler function, running on new thread to help
     * handle, initiate, and close peeer connections */
    static class Handler extends Thread {       
        
        private DataInputStream dataIn;
        private DataOutputStream dataOut;
        private Socket connection;

        //to check if handshake and bitfield send has been performed
        private boolean handshakeDone = false;
        private boolean bitfieldDone = false;

        //Initialise handshake buffer and peer variables
        private byte[] received = new byte[32]; // 32 set for handshake message
        private int pID = -1;
        int clientPeerID = -1;

        //To start and stop server
        boolean serverProc = true;

        //Hashmap for peer piece information
        HashMap<Integer, Boolean> peerPieceMap = new HashMap<Integer, Boolean>();

        //For sending piece messages,
        byte[] messageLength;
        byte[] messageType;
        byte[] indexField;

        public Handler(Socket connection, int pID) {
            this.connection = connection;
            this.pID = pID;
        }

        @Override
        public void run() {

            System.out.println("Connecting with: " + connection.toString());
            try {

                //initialize Input and Output streams
                dataOut = new DataOutputStream(connection.getOutputStream());
                dataIn = new DataInputStream(connection.getInputStream());

                //Should close the server or not
                boolean serverQuit = false;

                while (serverProc) {

                    if (!handshakeDone) {

                        //Waiting for the response first
                        dataIn.read(received); // read message into the msg 32 byte buffer
                        clientPeerID = ByteBuffer.wrap(Arrays.copyOfRange(received, 28, 32)).getInt();
                        neighborMap.put(clientPeerID, connection);

                        logConnectionFrom(pID, clientPeerID);

                        downloadRates.put(clientPeerID, 0); // set up client in map

                        // set up handshake message to send after receiving one from the client
                        String head = "P2PFILESHARINGPROJ"; // header
                        byte[] header = head.getBytes(); // header to bytes
                        byte[] zerobits = new byte[10]; // 10 byte zero bits
                        Arrays.fill(zerobits, (byte) 0);
                        byte[] peerID = ByteBuffer.allocate(4).putInt(pID).array(); // peer ID in byte array

                        // write all information to a byte array
                        byteStream.reset();
                        byteStream.write(header);
                        byteStream.write(zerobits);
                        byteStream.write(peerID);

                        byte[] handshake = byteStream.toByteArray();
                        sendMessage(handshake); // send handshake message to client

                        byteStream.reset();
                      
                    } else if (!bitfieldDone) {

                        // server is waiting for bitfield message from client
                        System.out.println("Peer: " + pID + " awaiting bitfield");
                        received = new byte[128]; // empty buffer

                        // server received bitfield message
                        dataIn.read(received, 0, 5); // incoming bitfield from client peer header

                        int bitfieldLength = ByteBuffer.wrap(Arrays.copyOfRange(received, 0, 4)).getInt();

                        byte[] bitfieldMessage = new byte[bitfieldLength];

                        dataIn.read(bitfieldMessage);
                        int counter = 1; // used to count pieces

                        for (int i = 0; i < bitfieldMessage.length; i++) {
                            String bs = String.format("%7s", Integer.toBinaryString(bitfieldMessage[i])).replace(' ',
                                    '0'); // ensure 0 bits are counted

                            for (int j = 0; j < bs.length(); j++) {
                                if (bs.charAt(j) == '0') {
                                    peerPieceMap.put(counter, false); // local connection map
                                } else if (bs.charAt(j) == '1') {
                                    peerPieceMap.put(counter, true); // local connection map
                                }
                                if (counter == numPieces) {
                                    break; // ignore the final 0 bits in the bitstrings
                                }
                                counter++;
                            }
                        }

                        logBitfieldFrom(pID, clientPeerID);
                        neighborsPieceMap.put(clientPeerID, peerPieceMap); // global (Peer) connection map

                        if (!peerPieceMap.containsValue(false)) {
                            System.out.println(pID + " says that " + clientPeerID + "is done!");
                            peersDone.replace(clientPeerID, true); // this peer is done
                        }

                        // send bitfield message

                        byte bitfield[] = initializeBitfield();

                        int payload = bitfield.length; // payload is done incorrectly when sending pieces //check???
                        messageLength = ByteBuffer.allocate(4).putInt(payload).array();
                        messageType = ByteBuffer.allocate(1).put(messageTypeMap.get("bitfield")).array();

                        byteStream.reset(); // make sure byteStream is empty
                        byteStream.write(messageLength);
                        byteStream.write(messageType);
                        byteStream.write(bitfield);
                        bitfieldMessage = byteStream.toByteArray();

                        sendMessage(bitfieldMessage);
                        System.out.println("post send bitfield to " + clientPeerID);

                        // empty out byteStream
                        byteStream.flush();
                        byteStream.reset();

                        // from here on, server will start processing regular messages
                        bitfieldDone = true;

                    } else {

                        while (dataIn.read(received) > -1) { // waiting for input

                            // retrieve message type
                            byte[] incomingMessageType = Arrays.copyOfRange(received, 4, 5);

                            // check the message type
                            if (Arrays.equals(incomingMessageType, messageTypeMap.get("interested"))) {

                                System.out.println("interested functionality");
                                sendPiece(1); // this needs to be changed!
                                // received = new byte[32];
                                // din.reset();
                            } else if (Arrays.equals(incomingMessageType, messageTypeMap.get("not_interested"))) {
                                System.out.println("not_interested functionality");
                            } else if (Arrays.equals(incomingMessageType, messageTypeMap.get("have"))) { // SERVER HAVE

                                int pieceNum = ByteBuffer.wrap(Arrays.copyOfRange(received, 5, 9)).getInt();
                                logHave(pID, clientPeerID, pieceNum);

                                peerPieceMap.replace(pieceNum, true); // the peer now has this piece
                                if (!peerPieceMap.containsValue(false)) {
                                    System.out.println(pID + " says that " + clientPeerID + "is done!");
                                }

                                received = new byte[20 + pieceSize]; // just for buffer

                            } else if (Arrays.equals(incomingMessageType, messageTypeMap.get("request"))) { // REQUEST
                                // if not choked
                                byte[] pieceNumToSend = Arrays.copyOfRange(received, 5, 9);
                                int pieceNumInt = ByteBuffer.wrap(pieceNumToSend).getInt();
                               
                                sendPiece(pieceNumInt);

                                peerPieceMap.replace(pieceNumInt, true);
                                if (!peerPieceMap.containsValue(false)) {
                                    peersDone.replace(clientPeerID, true);
                                    if (!peersDone.containsValue(false)) {
                                        System.out.println("shut down needed");
                                    }

                                }

                                received = new byte[5 + pieceSize];

                                if (serverQuit) {
                                    System.out.println("quit it true");
                                    dataOut.flush();
                                    // bis.close();
                                    // sc.close();
                                    // System.out.println("File Transfer Complete.");
                                    serverProc = false;
                                }
                            }
                        }

                    }
                    handshakeDone = true; // received handshake
                    received = new byte[5 + pieceSize];
                }
            } catch (IOException ioException) {
                System.out.println("Disconnect with Client " + clientPeerID);
                logger.warning("Disconnection with " + clientPeerID + " due to IOException");
            } finally {
                // Close connections
                try {
                    dataIn.close();
                    dataOut.close();
                    connection.close();
                } catch (IOException ioException) {
                    System.out.println("Disconnect with Client " + clientPeerID);
                    logger.warning("Disconnection with " + clientPeerID + " due to IOException");
                }
            }
        }

        // sending message function
        void sendMessage(byte[] msg) {
            try {
                dataOut.flush();
                dataOut.write(msg);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } catch (IOException ioException) {
                ioException.printStackTrace();
                logger.warning(pID + " was unable to send a message to " + clientPeerID);
            }
        }

        // method used for sending a specific piece
        void sendPiece(int pieceNumInt) throws IOException {

            byte[] pieceBuffer = pieceMap.get(pieceNumInt);
            int payload = pieceBuffer.length;

            // for header
            messageLength = ByteBuffer.allocate(4).putInt(payload).array();
            messageType = ByteBuffer.allocate(1).put(messageTypeMap.get("piece")).array();
            indexField = ByteBuffer.allocate(4).putInt(pieceNumInt).array(); // index of starting point

            byteStream.reset(); // make sure byteStream is empty

            // header
            byteStream.write(messageLength);
            byteStream.write(messageType); // should equal binary 7 for "piece"
            byteStream.write(indexField);

            // actual piece
            byteStream.write(pieceBuffer);

            byte[] sendMessage = byteStream.toByteArray();
            sendMessage(sendMessage); // sending the piece message
            byteStream.reset();

        }

    }
    
    static class Client extends Thread {

        Socket requestSocket;
        DataInputStream client_din;
        DataOutputStream client_dout;

        // whether handshake has been completed
        boolean handshakeDone = false;

        // whether bitfield has been completed
        boolean bitfieldDone = false;

        boolean start = false;

        boolean choked = false;

        // will be set from config and setup
        int pID = -1;
        int portNum = -1;
        // int numPieces = -1;
        String hostname = ""; // can be changed through constructor

        // empty buffers
        byte[] messageLength;
        byte[] messageType;
        byte[] indexField = new byte[4];

        // will be changed through handshake
        int serverPeerID = -1;

        /**
         * each "client" can only connect to one other server, and keeps track of that
         * map
         */
        HashMap<Integer, Boolean> peerPieceMap = new HashMap<Integer, Boolean>();
        // peer piece list (to make it easier to request pieces)
        ArrayList<Integer> peerPieceList = new ArrayList<>();

        // whether to continue running client loop
        boolean clientLoop = true;

        public Client(int portNum, int pID) {
            this.portNum = portNum;
            this.pID = pID;

        }

        public Client(int portNum, String hostname, int pID) {
            this.pID = pID;
            this.hostname = hostname;
            this.portNum = portNum;
        }

        public Client(int portNum, String hostname, int pID, Socket requestSocket) {
            this.pID = pID;
            this.hostname = hostname;
            this.portNum = portNum;
            this.requestSocket = requestSocket;
        }

        @Override
        public void run() {
            try {

                // just to be safe
                byteStream.reset();

                // input and output streams for client
                client_din = new DataInputStream(requestSocket.getInputStream());
                client_dout = new DataOutputStream(requestSocket.getOutputStream());
                client_dout.flush();

                while (clientLoop) {

                    if (!handshakeDone) {

                        // client sends handshake first

                        String headerStr = "P2PFILESHARINGPROJ"; // header
                        byte[] header = headerStr.getBytes(); // header to bytes
                        byte[] zerobits = new byte[10]; // 10 byte zero bits
                        Arrays.fill(zerobits, (byte) 0);
                        byte[] peerID = ByteBuffer.allocate(4).putInt(pID).array(); // peer ID in byte array
                                                                                          // format
                        // System.out.println("client sending my peer id: " + pID);

                        // write all information to a byte array
                        byteStream.reset();
                        byteStream.write(header);
                        byteStream.write(zerobits);
                        byteStream.write(peerID);

                        byte[] handshake = byteStream.toByteArray();

                        sendMessage(handshake); // client sends handshake message to server
                        byteStream.reset();

                        // client waiting for handshake mesage from server
                        byte[] incomingHandshake = new byte[32]; // empty byte array for incoming handshake

                        client_din.read(incomingHandshake); // read in the incoming handshake

                        // getting server peerID
                        byte[] checkServerID = Arrays.copyOfRange(incomingHandshake, 28, 32);
                        serverPeerID = ByteBuffer.wrap(checkServerID).getInt();
                        System.out.println("received server: " + serverPeerID);
                        logConnectionTo(pID, serverPeerID);

                        handshakeDone = true; // handshake received, do not do this part again
                        byteStream.reset();

                    } else if (!bitfieldDone) {
                        System.out.println("sending bitfield");
                        // client sends bitfield first
                        byte bitfield[] = initializeBitfield();

                        int payload = bitfield.length; // payload is done incorrectly when sending pieces /// check ?
                        messageLength = ByteBuffer.allocate(4).putInt(payload).array();
                        messageType = ByteBuffer.allocate(1).put(messageTypeMap.get("bitfield")).array();

                        byteStream.reset(); // make sure byteStream is empty
                        byteStream.write(messageLength);
                        byteStream.write(messageType);
                        byteStream.write(bitfield);
                        byte[] bitfieldMessage = byteStream.toByteArray();

                        sendMessage(bitfieldMessage);
                        System.out.println("post send bitfield to " + serverPeerID);

                        bitfieldDone = true;
                        byteStream.flush();
                        byteStream.reset();

                    } else { 
                        // Actual Messages

                        // read the first 5 bytes for message type and size
                        System.out.println("waiting for next message");
                        byte[] incomingMessage = new byte[5]; // only done once
                        boolean clientQuit = false; // used for when to quit to write to file

                        while (client_din.read(incomingMessage) > -1) {

                            // retrieve message type
                            byte[] messageSize = Arrays.copyOfRange(incomingMessage, 0, 4);
                            int messageSizeInt = ByteBuffer.wrap(messageSize).getInt();
                            byte[] messageType = Arrays.copyOfRange(incomingMessage, 4, 5); // getting the message type

                            if (Arrays.equals(messageType, messageTypeMap.get("choke"))) {
                                System.out.println("choke functionality");
                                choked = true;
                            } else if (Arrays.equals(messageType, messageTypeMap.get("bitfield"))) { // BITFIELD, only
                                                                                                     // done once

                                logBitfieldFrom(pID, serverPeerID);

                                int bitfieldLength = ByteBuffer.wrap(messageSize).getInt();
                                byte[] bitfieldMessage = new byte[bitfieldLength];

                                // read in the rest of the message
                                client_din.read(bitfieldMessage);
                                int counter = 1;

                                // update map with received bitfield from server
                                for (int i = 0; i < bitfieldMessage.length; i++) {
                                    String bs = String.format("%7s", Integer.toBinaryString(bitfieldMessage[i]))
                                            .replace(' ', '0'); // ensure that 0 bits are counted
                                    for (int j = 0; j < bs.length(); j++) {
                                        if (bs.charAt(j) == '0') {
                                            peerPieceMap.put(counter, false);
                                        } else if (bs.charAt(j) == '1') {
                                            if (!(pieceMap.containsKey(counter))) { // if client doesn't already have
                                                                                    // this piece
                                                peerPieceList.add(counter); // this is used to request pieces
                                            }
                                            peerPieceMap.put(counter, true);
                                        }
                                        if (counter == numPieces) {
                                            break;
                                        }
                                        counter++;
                                    }
                                }


                                neighborsPieceMap.put(serverPeerID, peerPieceMap); // update global map

                                if (!peerPieceMap.containsValue(false)) {
                                    peersDone.replace(serverPeerID, true);
                                }

                                // send the first request after sending the receiving the bitfield message
                                int requestPieceNum = selectRandomPieceNum();
                              
                                if (requestPieceNum < 0){
                                    System.out.println("dang");
                                }

                                // build header
                                messageLength = ByteBuffer.allocate(4).putInt(4).array(); // allocate 4 bytes for index
                                messageType = ByteBuffer.allocate(1).put(messageTypeMap.get("request")).array();
                                byte[] requestIndex = ByteBuffer.allocate(4).putInt(requestPieceNum).array();

                                byteStream.reset();

                                // header & request index
                                byteStream.write(messageLength);
                                byteStream.write(messageType);
                                byteStream.write(requestIndex);

                                byte[] msg = byteStream.toByteArray();
                                System.out.println("sending first request");
                                sendMessage(msg); // requesting the piece
                                byteStream.reset();

                            } else if (Arrays.equals(messageType, messageTypeMap.get("unchoke"))) {
                                System.out.println("unchoke functionality");
                                choked = false;
                            } else if (Arrays.equals(messageType, messageTypeMap.get("have"))) {

                                byte pieceNumBuff[] = new byte[4];
                                client_din.read(pieceNumBuff);
                                int pieceNum = ByteBuffer.wrap(pieceNumBuff).getInt();
                                logHave(pID, serverPeerID, pieceNum);

                                // add the peice to the neighbor's map, and then check if its done
                                peerPieceMap.replace(pieceNum, true); // the neighbor has this piece

                                // if we do not have this piece, add to the list of pieces to request
                                if (!pieceMap.containsKey(pieceNum)) {
                                    peerPieceList.add(pieceNum);
                                }

                                // if the server who sent the message is done, log that they are done
                                if (!peerPieceMap.containsValue(false)) {
                                    System.out.println(pID + " says that " + serverPeerID + "is done!");
                                    peersDone.replace(serverPeerID, true); // server is done, has all pieces
                                }

                            } else if (Arrays.equals(messageType, messageTypeMap.get("piece"))) { 

                                // client_din.read(incomingMessage, 5, 9); // check on this

                                byte[] fileIndex = Arrays.copyOfRange(incomingMessage, 5, 9);
                                int incomingPieceNumber = ByteBuffer.wrap(fileIndex).getInt(); // number corresponds to

                                byte[] newPiece = Arrays.copyOfRange(incomingMessage, 9, messageSizeInt + 1);

                                if (!pieceMap.containsKey(incomingPieceNumber)) { // don't want doubles
                                    pieceMap.put(incomingPieceNumber, newPiece);
                                    peerPieceList.remove(peerPieceList.indexOf(incomingPieceNumber));
                                }
                                logDownload(pID, serverPeerID, incomingPieceNumber);
                                // informPeers(incomingPieceNumber, serverPeerID); //will be used for have
                                // message

                                // THIS PIECE FUNCTIONALITY IS CURRENTLY CAUSING PROBLEMS

                                // send a new request

                                int requestPieceNum = selectRandomPieceNum(); 
                                // get a piece number to request from the
                                // server

                                if (requestPieceNum > 0) {

                                    // need to add a check in here to make sure we dont have it already
                                    messageLength = ByteBuffer.allocate(4).putInt(4).array(); // allocate 4 bytes for
                                                                                              // index
                                    messageType = ByteBuffer.allocate(1).put(messageTypeMap.get("request")).array();
                                    byte[] requestIndex = ByteBuffer.allocate(4).putInt(requestPieceNum).array();

                                    byteStream.reset();
                                    byteStream.write(messageLength);
                                    byteStream.write(messageType);
                                    byteStream.write(requestIndex);

                                    byte[] msg = byteStream.toByteArray();
                                    // System.out.println("sending another request");
                                    sendMessage(msg); // requesting the piece
                                    byteStream.reset();
                                } else {
                                    System.out.println("-1 for " + serverPeerID);
                                }

                                // peer has all the pieces
                                System.out.println("piece map size: " + pieceMap.size());
                                System.out.println("numPieces: " + numPieces);
                                if (pieceMap.size() == numPieces) {
                                    // clientLoop = false;
                                    // System.out.println("755 true");
                                    clientQuit = true;
                                }

                            }
                            // client loop stays open to maintain connection until end of program
                            if (clientQuit) {

                                System.out.println("quit");
                                // when done, we need to write the file to the client's folder
                                logDone(pID);
                                byteStream.reset();

                                // get all the pieces from the piece map
                                for (int i = 1; i <= pieceMap.size(); i++) {
                                    byteStream.write(pieceMap.get(i)); // write all pieces from map into byteStream
                                }
                                byte[] finalFile = byteStream.toByteArray();

                                String pathname = file.getAbsolutePath() + "/" + "copy.txt"; 
                               
                                // stored in the user's folder
                                File copiedFile = new File(pathname);

                                // write out the final file
                                try (FileOutputStream fos = new FileOutputStream(copiedFile)) {
                                    fos.write(finalFile);
                                }

                                // System.out.println("Client: final file has been written");
                                byteStream.flush();
                                byteStream.reset();

                                // Now, this client becomes an uploader only
                                clientLoop = false;
                            }

                            incomingMessage = new byte[100 + pieceSize];
                            // Thread.sleep(1000);
                        }
                    }
                }
            } catch (ConnectException e) {
                logger.warning("Connection refused. No server initiated.");
                System.err.println("Connection refused. You need to initiate a server first.");
            } catch (UnknownHostException unknownHost) {
                logger.warning("Connection refused. Unknown host.");
                System.err.println("You are trying to connect to an unknown host!");
            } catch (IOException ioException) {
                ioException.printStackTrace();
            } finally {
                // Close connections
                try {
                    requestSocket.close();
                } catch (IOException ioException) {
                    ioException.printStackTrace();
                }
            }
        }

        void sendMessage(byte[] msg) {
            try {
                client_dout.flush();
                client_dout.write(msg);

            } catch (IOException ioException) {
                ioException.printStackTrace();
            }
        }

        int selectRandomPieceNum() {
            int counter = 0;
            while (counter < peerPieceList.size()) {
                int pieceNum = peerPieceList.get(random.nextInt(peerPieceList.size()));
                if (!pieceMap.containsKey(pieceNum)) { // if we dont have this piece
                    return pieceNum;
                } else {
                    // remove the piece from the list if we already have it
                    peerPieceList.remove(peerPieceList.indexOf(pieceNum));
                }
                counter++;
            }
            return -1;
        }
    }

    //Hashmap for message
    static HashMap<String, byte[]> createMessageHashMap() {
        HashMap<String, byte[]> map = new HashMap<String, byte[]>();
        byte zero = 0b000;
        byte one = 0b001;
        byte two = 0b010;
        byte three = 0b011;
        byte four = 0b100;
        byte five = 0b101;
        byte six = 0b110;
        byte seven = 0b111;
        // byte zeroArr[] = ByteBuffer.allocate(1).put(zero).array();
        map.put("choke", ByteBuffer.allocate(1).put(zero).array());
        map.put("unchoke", ByteBuffer.allocate(1).put(one).array());
        map.put("interested", ByteBuffer.allocate(1).put(two).array());
        map.put("not_interested", ByteBuffer.allocate(1).put(three).array());
        map.put("have", ByteBuffer.allocate(1).put(four).array());
        map.put("bitfield", ByteBuffer.allocate(1).put(five).array());
        map.put("request", ByteBuffer.allocate(1).put(six).array());
        map.put("piece", ByteBuffer.allocate(1).put(seven).array());

        return map;
    }

    //Generate BitField messages that are sent after the handshake
    static byte[] initializeBitfield() {

        String bitstring = "";

        for (int i = 1; i <= numPieces; i++) {

            if (bitstring.equals("")) { // first bit in the bit string must be 0
                bitstring = "0";
            }

            if (pieceMap.containsKey(i)) { // if the map contains the key, add 1, if not add 0
                bitstring += "1";
            } else {
                // System.out.println ("no " + i);
                bitstring += "0";
            }

            if (i % 7 == 0 && i != 1) { // every 8 bits the bitstring must be written out and reset
                byte b = Byte.parseByte(bitstring, 2);
                byteStream.write(b);
                // System.out.println("Bitstring: " + bitstring);
                bitstring = "";
            }

            if (i == numPieces) { // at the end of the map, all remaining bits are 0
                int bsLength = bitstring.length();
                int j = 7 - bsLength;

                for (int k = 0; k <= j; k++) {
                    bitstring += "0";
                }
                byte b = Byte.parseByte(bitstring, 2);
                byteStream.write(b);
            }
        }

        byte[] bitfield = byteStream.toByteArray();
        System.out.println("returning generated bitfield");
        return bitfield;
    }

    static void informPeers(int haveIndex, int serverID) throws IOException {

        for (int n : neighborMap.keySet()) {
            if (n != serverID) { // dont tell the person who just sent it to you

                Socket tempSocket = neighborMap.get(n);
                System.out.println("sending have to neighbor " + n);
                // DataOutputStream all_dout = new
                // DataOutputStream(tempSocket.getOutputStream());
                OutputStream temp = tempSocket.getOutputStream();

                byte[] messageLength = ByteBuffer.allocate(4).putInt(4).array(); // allocate 4 bytes for index
                byte[] messageType = ByteBuffer.allocate(1).put(messageTypeMap.get("have")).array();
                byte[] requestIndex = ByteBuffer.allocate(4).putInt(haveIndex).array();
                byteStream.reset();
                byteStream.write(messageLength);
                byteStream.write(messageType);
                byteStream.write(requestIndex);

                byte[] msg = byteStream.toByteArray();
                temp.flush();
                temp.write(msg);
                byteStream.reset();
            }

        }
    }

    static void logConnectionTo(int peerID1, int peerID2) {
        logger.info("Peer [" + peerID1 + "] made a connection to Peer [" + peerID2 + "]");
    }

    static void logConnectionFrom(int peerID1, int peerID2) {
        logger.info("Peer [" + peerID1 + "] is connected from Peer [" + peerID2 + "]");
    }

    static void logBitfieldFrom(int peerID1, int peerID2) {
        logger.info("Peer [" + peerID1 + "] has received a bitfield message from [" + peerID2 + "]");
    }

    static void logchangeNeighbors(int peerID1, int[] peerList) {
        logger.info("Peer [" + peerID1 + "] has the preferred neighbors [" + Arrays.toString(peerList) + "]");
    }

    static void logchangeOpUnchokeNeighbor(int peerID1, int opUnNeighbor) {
        logger.info("Peer [" + peerID1 + "] has the optimistically unchoked neighbor [" + opUnNeighbor + "]");
    }

    static void logUnchoked(int peerID1, int peerID2) {
        logger.info("Peer [" + peerID1 + "] is unchoked by [" + peerID2 + "]");
    }

    static void logChoked(int peerID1, int peerID2) {
        logger.info("Peer [" + peerID1 + "] is choked by [" + peerID2 + "]");
    }

    static void logHave(int peerID1, int peerID2, int pieceNum) {
        logger.info("Peer [" + peerID1 + "] received the 'have' message from [" + peerID2 + "] for the piece ["
                + pieceNum + "]");
    }

    static void logInterested(int peerID1, int peerID2) {
        logger.info("Peer [" + peerID1 + "] received the 'interested' message from [" + peerID2);
    }

    static void logNotInterested(int peerID1, int peerID2) {
        logger.info("Peer [" + peerID1 + "] received the 'not interested' message from [" + peerID2);
    }

    static void logDownload(int peerID1, int peerID2, int pieceNum) {
        logger.info("Peer [" + peerID1 + "] has downloaded the piece [" + pieceNum + "] from [" + peerID2 + "]. "
                + "Now the number of pieces it has is " + pieceMap.size() + ".");
    }

    static void logDone(int peerID) {
        logger.info("Peer [" + peerID + "] has downloaded the complete file.");
    }

    static void updatePeersDone(int peerID) {
        boolean done = true;
        peersDone.put(peerID, true);

        for (int peer : peersDone.keySet()) {
            if (peersDone.get(peer) == false) {
                done = false;
            }
        }

        if (done) {
            System.out.println("The Complete file has been received by every peer now");
            System.exit(0);
        }
    }
}