import java.util.*;

import java.util.logging.*;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

public class Peer2 {

    static Logger logger = Logger.getLogger("Log");
    static FileHandler fileHandler; // for log
    static File file; // new directory for peer
    static Random random= new Random();

    // to be changed with config file
    static String fileName = "";
    static int pieceSize = -1;
    static int floatPiece = -1;
    static double fileSize = -1;
    static int numPieces = -1;
    static int numNeighbors = -1;
    static int unchokingInterval = -1;
    static int optomisticUnchokingInterval = -1;

    // connection information about all peers
    // this is used to connect peers
    static HashMap<Integer, String[]> peerInfoMap = new HashMap<Integer, String[]>();

    // keep track of which file pieces SELF has
    // this is accesible through all client and server threads
    static HashMap<Integer, byte[]> pieceMap = new HashMap<>();

    // map of EACH peer and their piece map
    // the map is as follows:
    // key: peerNumber value: map of (key: piece num, value: boolean of whether the
    // peer has the piece)
    // this is filled for each peer when setting up bitfield
    // when there are NO values of "false" in this map, all peers have all pieces.
    // we will need to periodically check this map, probably after receiving "have"
    // messages.
    static HashMap<Integer, HashMap<Integer, Boolean>> peersPieceMap = new HashMap<>();

    // message types to numbers for easy access
    static HashMap<String, byte[]> messageTypeMap;

    // list of neighbors by connection sockets, peer ID's don't need to be stored in
    // this case
    // this is used to send "have" messages to all neighbors
    static ArrayList<Socket> neighbors = new ArrayList<>();
    static ArrayList<DataOutputStream> neighbor_douts = new ArrayList<>();

    // download rates map
    static HashMap<Socket, Integer> downloadRates = new HashMap<>();

    static ArrayList<Socket> chokedNeighbors = new ArrayList<>();
    static ArrayList<Socket> unchokedNeighbors = new ArrayList<>();

    // to be changed with peer info
    static int portNum = -1;
    static int isOwner = -1;
    static int myPeerID = -1;

    static ByteArrayOutputStream peerbyteStream = new ByteArrayOutputStream();

    public static void main(String[] args) throws IOException {

        // self id
        myPeerID = Integer.valueOf(args[0]);
        messageTypeMap = createMessageHashMap();

        // method to create a new logger for peer
        createLogger();

        // set properties from config
        getProperties();

        // get all peer info from config file
        createPeerInfoMap();

        // get self information
        getSelfInfo();

        // client server connection process
        boolean peerConnected = false;
        boolean fileReady = false;

        // start a new serverSocket at the port number
        ServerSocket serverSocket = new ServerSocket(portNum);

        Timer timer = new Timer();

        timer.schedule(new Choke(), 0, unchokingInterval * 1000);
        try {
            while (true) {
                if (isOwner == 1) { // if this peer is an owner of the file
                    if (!fileReady) {
                        fileToPieceMap();
                        fileReady = true;
                    }

                    // Socket peerSocket = serverSocket.accept();
                    // neighbors.add(peerSocket);
                    // neighbor_douts.add(new DataOutputStream(peerSocket.getOutputStream()));
                    // new Handler(peerSocket).start();
                }
                if (!peerConnected) { // set up clients first
                    for (int id : peerInfoMap.keySet()) {

                        String[] peerInfo = peerInfoMap.get(id);

                        // connect to each peer that already has a server running
                        // it to work this way, peers need to be established in peerNum order
                        peersPieceMap.put(id, new HashMap<>()); // set up the piece map for each peer
                        if (id < myPeerID) {

                            String hostId = peerInfo[0];
                            int hostPort = Integer.valueOf(peerInfo[1]);

                            System.out.println("connecting to peerid " + id + " at host " + hostId + " port "
                                    + hostPort);
                            Socket requestSocket = new Socket(hostId, hostPort);

                            // adding to map neighbors of ALL neighbors (both peers we are clients of and
                            // peers we are servers of)
                            neighbors.add(requestSocket);

                            // add to map douts of ALL douts
                            neighbor_douts.add(new DataOutputStream(requestSocket.getOutputStream()));
                            downloadRates.put(requestSocket, 0);
                            new Handler(requestSocket, id).start();
                            logConnectionTo(myPeerID, id);
                            Thread.sleep(500); // as to not overload
                        }

                    }
                    peerConnected = true;
                }

                // finished connecting to all open servers, now waiting for peers to connect
                Socket peerSocket = serverSocket.accept();
                neighbor_douts.add(new DataOutputStream(peerSocket.getOutputStream()));
                downloadRates.put(peerSocket, 0);
                new Handler(peerSocket).start();
                // timer.schedule(new Choke(), 0, unchokingInterval * 1000);

            }
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } finally {
            serverSocket.close();
        }

    }

    static class Handler extends Thread {

        Socket connection;

        private DataOutputStream dout;
        private DataInputStream din;

        private boolean selfChoked = false;
        // private boolean peerChoked = false;
        private boolean done = false;

        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();

        // private int clientPeerID;
        private int connectedPeerID = -1;

        public Handler(Socket connection) {
            this.connection = connection;
        }

        public Handler(Socket connection, int connectedPeerID) {
            this.connection = connection;
            this.connectedPeerID = connectedPeerID;
        }

        // this list is used to know which pieces to request from the peer
        private ArrayList<Integer> peerPieceList = new ArrayList<>();

        public void run() {

            try {

                this.dout = new DataOutputStream(connection.getOutputStream());
                this.din = new DataInputStream(connection.getInputStream());

                System.out.println("my connection: " + connection.toString());
                System.out.println("my peer id:" + myPeerID);
                // System.out.println("server id:" + this.connectedPeerID);

                sendHandshake();

                byte[] incomingHandshake = new byte[32];
                this.din.read(incomingHandshake);

                parseHandshake(incomingHandshake);
                System.out.println("connected to peer: " + this.connectedPeerID);

                sendBitfield();

                // peer waits for bitfield message
                byte[] messageLength = new byte[4];
                byte[] messageType = new byte[1];
                this.din.read(messageLength); // first get the message length to set up the bitfield
                this.din.read(messageType);
                int bitfieldLength = ByteBuffer.wrap(messageLength).getInt();

                // "client" peer receives bitfield message from "server" peer
                if (Arrays.equals(messageType, messageTypeMap.get("bitfield"))) {
                    // System.out.println(myPeerID + " received bitfield message from " +
                    // this.serverPeerID);
                    byte[] bitfieldMessage = new byte[bitfieldLength];
                    this.din.read(bitfieldMessage);

                    parseBitfield(bitfieldMessage);
                }

                messageLength = new byte[4];
                messageType = new byte[1];

                if (isOwner == 0 && !this.selfChoked) { // if not owner, request the first piece

                    // send the first request before the client loop starts
                    int requestPieceNum = selectRandomPieceNum();

                    // only send a request if there are available pieces to request
                    if (requestPieceNum > 0) {
                        sendRequest(requestPieceNum);
                    }

                }

                while (this.din.read(messageLength) > -1) {

                    this.din.read(messageType);

                    if (Arrays.equals(messageType, messageTypeMap.get("request"))) {
                        System.out.println(myPeerID + " received request message from " + this.connectedPeerID);

                        // parse the request and send the piece if the peer is not choked
                        if (!chokedNeighbors.contains(this.connection)) {
                            byte[] requestIndex = new byte[ByteBuffer.wrap(messageLength).getInt()];
                            this.din.read(requestIndex);
                            sendPiece(requestIndex);

                        }

                    } else if (Arrays.equals(messageType, messageTypeMap.get("piece"))) {
                        // System.out.println(myPeerID + " recieved a piece from " + this.serverPeerID);

                        byte[] pieceIndex = new byte[4];
                        byte[] newPiece = new byte[ByteBuffer.wrap(messageLength).getInt()];

                        this.din.read(pieceIndex);
                        this.din.read(newPiece);
                        parseNewPiece(pieceIndex, newPiece);

                        // log that a piece was received, increase the download rate
                        downloadRates.replace(connection, downloadRates.get(connection) + 1);

                        // Thread.sleep(500);
                        int newRequestPieceNum = selectRandomPieceNum();

                        if (newRequestPieceNum > 0) {
                            // Thread.sleep(300); // for the sake of not overloading the connection
                            sendRequest(newRequestPieceNum);
                        } else {

                            // System.out.println(peersPieceMap.get(myPeerID).toString());
                            // System.out.println(peerPieceList.toString());
                            System.out.println("received -1 for " + this.connectedPeerID);
                        }
                    } else if (Arrays.equals(messageType, messageTypeMap.get("have"))) { // on receving this have, we
                                                                                         // should update peerPieceMap

                        byte[] haveIndex = new byte[4];

                        this.din.read(haveIndex);
                        int haveIndexInt = ByteBuffer.wrap(haveIndex).getInt();

                        // updating the overall piece map for the client peer we are connected to
                        if (peersPieceMap.get(this.connectedPeerID).containsKey(haveIndexInt)) {
                            peersPieceMap.get(this.connectedPeerID).replace(haveIndexInt, true);
                        } else {
                            peersPieceMap.get(this.connectedPeerID).put(haveIndexInt, true);
                        }

                        if (!pieceMap.containsKey(haveIndexInt)) {
                            this.peerPieceList.add(haveIndexInt); // if we don't have this piece, we can now ask this
                                                                  // server
                        }

                        if (!peerPieceList.isEmpty()) {
                            // send an interested message
                        }

                        if (isOwner == 0 && !this.selfChoked) {

                            int newRequestPieceNum = selectRandomPieceNum();

                            if (newRequestPieceNum > 0) {
                                // Thread.sleep(300);
                                sendRequest(newRequestPieceNum);
                            } else {
                                // System.out.println(peersPieceMap.get(myPeerID).toString());
                                // System.out.println(peerPieceList.toString());
                                System.out.println("received -1 for " + this.connectedPeerID);
                            }

                            logHave(myPeerID, this.connectedPeerID, ByteBuffer.wrap(haveIndex).getInt());

                            // TO DO HERE:
                            // check if all peers are done after receiving this have message
                            // if we are done, move to a file out method/portion/whatever

                        }

                    } else if (Arrays.equals(messageType, messageTypeMap.get("choke"))) {
                        logChoked(myPeerID, this.connectedPeerID);
                        this.selfChoked = true;
                    } else if (Arrays.equals(messageType, messageTypeMap.get("unchoke"))) {
                        logUnchoked(myPeerID, this.connectedPeerID);
                        this.selfChoked = false;
                    } else if (Arrays.equals(messageType, messageTypeMap.get("interested"))) {
                        System.out.println("interetsed");
                    } else if (Arrays.equals(messageType, messageTypeMap.get("not_interested"))) {
                        System.out.println("not interetsed");
                    } else {
                        System.out.println("received unknown message type");
                    }

                    if (pieceMap.size() == numPieces && !done) {
                        logDone(myPeerID);
                        done = true;
                    }

                    this.dout.flush();
                    this.byteStream.flush();
                    this.byteStream.reset();
                    messageLength = new byte[4];
                    messageType = new byte[1];
                    System.out.println("waiting for next message from " + this.connectedPeerID);
                    // System.out.println("my piece map: " + pieceMap.size());
                    // System.out.println("numpieces: " + numPieces);
                }

                // start the client loop
                messageLength = new byte[4];
                messageType = new byte[1];

            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

        }

        // sending handshake message to "server" peer
        void sendHandshake() throws IOException, InterruptedException {

            byte[] header = new String("P2PFILESHARINGPROJ").getBytes(); // header
            byte[] zerobits = new byte[10]; // 10 byte zero bits
            Arrays.fill(zerobits, (byte) 0);
            byte[] peerID = ByteBuffer.allocate(4).putInt(myPeerID).array(); // peer ID in byte array

            // write all information to a byte array
            this.byteStream.reset();
            this.byteStream.write(header);
            this.byteStream.write(zerobits);
            this.byteStream.write(peerID);

            byte[] handshake = this.byteStream.toByteArray();

            sendMessage(handshake); // client sends handshake message to server

            this.byteStream.reset();

        }

        // parse incoming handshake from "server"
        void parseHandshake(byte[] incomingHandshake) {
            this.connectedPeerID = ByteBuffer.wrap(Arrays.copyOfRange(incomingHandshake, 28, 32)).getInt();
            logConnectionFrom(myPeerID, this.connectedPeerID);
        }

        void sendBitfield() throws IOException, InterruptedException {
            // System.out.println(myPeerID + " sending bitfield");
            byte bitfield[] = initializeBitfield();
            int payload = bitfield.length; // payload is done incorrectly when sending pieces /// check ?
            byte[] messageLength = ByteBuffer.allocate(4).putInt(payload).array();
            byte[] messageType = ByteBuffer.allocate(1).put(messageTypeMap.get("bitfield")).array();

            this.byteStream.reset(); // make sure byteStream is empty
            this.byteStream.write(messageLength);
            this.byteStream.write(messageType);
            this.byteStream.write(bitfield);
            byte[] bitfieldMessage = this.byteStream.toByteArray();

            sendMessage(bitfieldMessage);
            System.out.println(myPeerID + " sent bitfield message to " + this.connectedPeerID);

        }

        // parse incoming bitfield from server
        void parseBitfield(byte[] bitfieldMessage) {

            int counter = 1; // used to count pieces

            if (!peersPieceMap.containsKey(this.connectedPeerID)) {
                peersPieceMap.put(this.connectedPeerID, new HashMap<>());
            }

            for (int i = 0; i < bitfieldMessage.length; i++) {

                String bs = String.format("%7s", Integer.toBinaryString(bitfieldMessage[i])).replace(' ', '0'); // ensure

                for (int j = 0; j < bs.length(); j++) {
                    if (bs.charAt(j) == '0') {
                        // peerPieceMap.put(counter, false); // local connection map
                        peersPieceMap.get(this.connectedPeerID).put(counter, false); // changing the global static map
                    } else if (bs.charAt(j) == '1') {
                        // peerPieceMap.put(counter, true); // local connection map
                        peersPieceMap.get(this.connectedPeerID).put(counter, true);
                        if (!pieceMap.containsKey(counter)) { // if this client doesn't have this piece, then it can
                                                              // request from server
                            this.peerPieceList.add(counter); // add to list of requests to server
                        }
                    }
                    // System.out.println("counter: " + counter);
                    if (counter == numPieces) {
                        // System.out.println("peerPieceList: " + peerPieceList.toString());
                        break; // ignore the final 0 bits in the bitstrings

                    }
                    counter++;
                }
                if (counter == numPieces + 1) {
                    break;
                }
            }

            // System.out.println("Possible requests for " + this.serverPeerID + " are: " +
            // this.serverPieceList.toString());
            logBitfieldFrom(myPeerID, this.connectedPeerID);
            // System.out.println(peersPieceMap.get(this.serverPeerID).toString());
        }

        // selecting a random piece number based on "serverPieceList"
        int selectRandomPieceNum() {
            System.out.println("selecting a random piece number");
            while (this.peerPieceList.size() > 0) {
                int pieceNum = this.peerPieceList.get(random.nextInt(this.peerPieceList.size()));
                if (!pieceMap.containsKey(pieceNum)) { // if we dont have this piece
                    return pieceNum;
                } else {
                    // remove the piece from the list if we already have it
                    this.peerPieceList.remove(this.peerPieceList.indexOf(pieceNum));
                }
            }
            return -1;
        }

        // send a piece request to a peer
        void sendRequest(int requestPieceNum) throws IOException, InterruptedException {

            byte[] messageLength = ByteBuffer.allocate(4).putInt(4).array();
            byte[] messageType = ByteBuffer.allocate(1).put(messageTypeMap.get("request")).array();
            byte[] requestIndex = ByteBuffer.allocate(4).putInt(requestPieceNum).array();

            this.byteStream.reset();

            // header & request index
            this.byteStream.write(messageLength);
            this.byteStream.write(messageType);
            this.byteStream.write(requestIndex);

            byte[] msg = this.byteStream.toByteArray();
            System.out.println(myPeerID + " sending request " + requestPieceNum + " to " + this.connectedPeerID);
            sendMessage(msg); // requesting the piece
            this.byteStream.reset();
        }

        // "server" peer sends piece of byte file to "client" peer
        void sendPiece(byte[] requestIndex) throws IOException, InterruptedException {

            int pieceNum = ByteBuffer.wrap(requestIndex).getInt();
            System.out.println(myPeerID + " sending piece " + pieceNum + " to " + this.connectedPeerID);
            byte[] piece = pieceMap.get(pieceNum);

            int payload = piece.length;

            byte[] messageLength = ByteBuffer.allocate(4).putInt(payload).array();
            byte[] messageType = ByteBuffer.allocate(1).put(messageTypeMap.get("piece")).array();
            byte[] indexField = ByteBuffer.allocate(4).putInt(pieceNum).array(); // index of starting point

            this.byteStream.reset(); // make sure byteStream is empty

            // header
            this.byteStream.write(messageLength);
            this.byteStream.write(messageType); // should equal binary 7 for "piece"
            this.byteStream.write(indexField);

            // actual piece
            this.byteStream.write(piece);

            byte[] sendMessage = byteStream.toByteArray();
            // Thread.sleep(250);
            sendMessage(sendMessage); // sending the piece message
            // System.out.println("Sending to client: ");
            // String test = new String(piece, StandardCharsets.UTF_8);
            // System.out.println(test);

            this.byteStream.reset();
        }

        // what to do with new piece
        void parseNewPiece(byte[] pieceIndex, byte[] newPiece) throws IOException {

            int pieceNum = ByteBuffer.wrap(pieceIndex).getInt();
            byte[] newPieceCopy = Arrays.copyOf(newPiece, newPiece.length);

            if (!pieceMap.containsKey(pieceNum)) {
                pieceMap.put(pieceNum, newPieceCopy);
                this.peerPieceList.removeAll(Collections.singleton(pieceNum));
                sendHave(pieceNum); // letting all peers know about the new piece
            }

            logDownload(myPeerID, this.connectedPeerID, pieceNum);

        }

        // letting all the neighbors know that we have receieved a new piece
        // this information is used to check the peerpiecemap
        void sendHave(int pieceNum) throws IOException {

            byte[] messageLength = ByteBuffer.allocate(4).putInt(4).array(); // allocate 4 bytes for index
            byte[] messageType = ByteBuffer.allocate(1).put(messageTypeMap.get("have")).array();
            byte[] requestIndex = ByteBuffer.allocate(4).putInt(pieceNum).array();

            this.byteStream.reset();
            this.byteStream.write(messageLength);
            this.byteStream.write(messageType);
            this.byteStream.write(requestIndex);

            byte[] msg = this.byteStream.toByteArray();

            this.byteStream.reset();

            for (DataOutputStream n_dout : neighbor_douts) {
                n_dout.flush();
                n_dout.write(msg);
            }
        }

        void sendMessage(byte[] msg) throws IOException, InterruptedException {
            Thread.sleep(250);
            System.out.println(myPeerID + " sending message to " + this.connectedPeerID);

            this.dout.flush();
            this.dout.write(msg);
        }

    }

    static class Choke extends TimerTask {

        @Override
        public void run() {

            HashMap<Socket, Integer> toUnchoke = new HashMap<>();
            HashMap<Socket, Integer> toChoke = new HashMap<>();

            // ArrayList<Socket> toUnchoke = new ArrayList<>();
            // TODO Auto-generated method stub
            // System.out.println("CHOKING!");
            for (Socket conn : downloadRates.keySet()) {
                int rate = downloadRates.get(conn);

                if (toUnchoke.size() < numNeighbors) {
                    toUnchoke.put(conn, rate);
                } else {
                    int smallestInMap = Collections.min(toUnchoke.values());
                    for (Socket temp : toUnchoke.keySet()) {
                        if (toUnchoke.get(temp) == smallestInMap) { // check if the incoming rate is higher than the
                                                                    // smallest in the map
                            if (rate > toUnchoke.get(temp)) { // if another peer has a higher rate, remove temp and
                                                              // replace
                                toUnchoke.remove(temp);
                                toUnchoke.put(conn, rate);
                            } else {
                                toChoke.put(conn, rate);
                            }
                        }

                    }
                }
                // go through the download rates map, and pick the top x max based on config
            }

            peerbyteStream.reset();
            try {

                byte[] messageLength = ByteBuffer.allocate(4).putInt(0).array();
                byte[] messageType = ByteBuffer.allocate(1).put(messageTypeMap.get("choke")).array();

                peerbyteStream.write(messageLength);
                peerbyteStream.write(messageType);

                byte[] msg = peerbyteStream.toByteArray();

                for (Socket conn : toChoke.keySet()) {
                    if (!chokedNeighbors.contains(conn)) {
                        DataOutputStream temp_dout = new DataOutputStream(conn.getOutputStream());
                        temp_dout.write(msg);
                    }
                }

                chokedNeighbors = new ArrayList<>(toChoke.keySet());

                messageLength = ByteBuffer.allocate(4).putInt(0).array(); // allocate 4 bytes for index
                messageType = ByteBuffer.allocate(1).put(messageTypeMap.get("unchoke")).array();

                peerbyteStream.reset();
                peerbyteStream.write(messageLength);
                peerbyteStream.write(messageType);

                msg = peerbyteStream.toByteArray();

                for (Socket conn : toUnchoke.keySet()) {
                    if (!unchokedNeighbors.contains(conn)) {
                        DataOutputStream temp_dout = new DataOutputStream(conn.getOutputStream());
                        temp_dout.write(msg);
                    }
                }

                unchokedNeighbors = new ArrayList<>(toUnchoke.keySet());
                downloadRates.replaceAll((key, oldVal) -> 0);

            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

        }

    }

    // create a logger for the peer
    static void createLogger() {
        file = new File(System.getProperty("user.dir") + "/" + myPeerID);
        file.mkdir();
        String logFile = file.getAbsolutePath() + "/" + myPeerID + "_BitTorrent.log";

        try {

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
    }

    static void getProperties() {
        try (InputStream input = new FileInputStream("config.properties")) {

            Properties prop = new Properties();

            // load a properties file
            prop.load(input);

            // get properties
            fileName = prop.getProperty("FileName");
            pieceSize = Integer.valueOf(prop.getProperty("PieceSize"));
            fileSize = Integer.valueOf(prop.getProperty("FileSize"));
            numPieces = (int) Math.ceil(fileSize / pieceSize); // total number of pieces we will need to transfer

            unchokingInterval = Integer.valueOf(prop.getProperty("UnchokingInterval"));
            optomisticUnchokingInterval = Integer.valueOf(prop.getProperty("OptimisticUnchokingInterval"));
            numNeighbors = Integer.valueOf(prop.getProperty("NumberOfPreferredNeighbors"));

        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    static void createPeerInfoMap() throws IOException {

        BufferedReader reader = new BufferedReader(new FileReader("LocalP.txt"));

        String line = reader.readLine();

        // store the connection info of each peer
        while (line != null) {
            String readLine[] = line.split(" ");
            int tempPeerID = Integer.valueOf(readLine[0]);
            String peerInfo[] = Arrays.copyOfRange(readLine, 1, 4);
            peerInfoMap.put(tempPeerID, peerInfo);
            line = reader.readLine();
        }
        reader.close();

    }

    static void getSelfInfo() {
        String[] thisPeer = peerInfoMap.get(myPeerID);
        portNum = Integer.valueOf(thisPeer[1]);
        isOwner = Integer.valueOf(thisPeer[2]);
        System.out.println("I am peer " + myPeerID + " listening on port " + portNum + " and owner: " + isOwner);
    }

    // goes through piece map to check which pieces it has
    static byte[] initializeBitfield() {

        String bitstring = "";
        peerbyteStream.reset();

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
                peerbyteStream.write(b);
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
                peerbyteStream.write(b);
            }
        }

        byte[] bitfield = peerbyteStream.toByteArray();
        return bitfield;
    }

    // only used on the owner of the file
    static void fileToPieceMap() throws IOException {
        File file = new File(fileName);

        byte[] buffer = new byte[pieceSize];
        int counter = 1;

        // putting contents of the file into a map
        try (FileInputStream fileInputStream = new FileInputStream(file);
                BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream)) {

            int bytesRead = 0;
            // add each piece of the file to a map
            while ((bytesRead = bufferedInputStream.read(buffer)) > 0) {
                // System.out.println ("Bytes read: " + bytesRead);
                // byteStream.write(buffer, 0, pieceSize);
                peerbyteStream.write(buffer, 0, bytesRead);
                byte[] piece = peerbyteStream.toByteArray();
                pieceMap.put(counter, piece);
                peerbyteStream.flush();
                peerbyteStream.reset();

                counter++;

            }
        }
    }

    // map used for message typing
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
}
