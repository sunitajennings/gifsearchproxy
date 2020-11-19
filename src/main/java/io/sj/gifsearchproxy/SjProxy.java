package io.sj.gifsearchproxy;

import static io.sj.gifsearchproxy.Utils.isAllowed;
import static io.sj.gifsearchproxy.Utils.logerror;
import static io.sj.gifsearchproxy.Utils.loginfo;
import static io.sj.gifsearchproxy.Utils.logtrace;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLServerSocketFactory;

public class SjProxy implements Runnable {

	private static final String _HTTP_200_CONNECTION_OK = "200 Connection Established";
	private static final String _HTTP_403_FORBIDDEN = "403 Forbidden";
	private static final String _HTTP_405_NOT_ALLOWED = "405 Method Not Allowed";
	private static final String _HTTP_502_BAD_GATEWAY = "502 Bad Gateway";
	
	private static int _LISTENING_PORT = 8443; //default value, can be overridden by cmd line option
	
	private Socket client;
	
	public SjProxy (Socket clientsocket) {
		this.client = clientsocket;
	}
	
	public static void main(String[] args) {
		Map<String, String> options = getCLI_options(args);
		Utils.logStartupDetails(options);
		
		runProxy();
	}
	
	private static Map<String,String> getCLI_options(String[] inputArgs) {
		Map<String,String> argMap = new HashMap<String, String>();
		for (String arg : inputArgs) {
			String[] keyvalue = arg.split("=");
			
			if (keyvalue.length == 2)
				argMap.put(keyvalue[0], keyvalue[1]);
			else if (keyvalue.length == 1)
				argMap.put(keyvalue[0], "true");
		}
		
		
		if (argMap.containsKey("port")) _LISTENING_PORT = Integer.parseInt(argMap.get("port"));
		
		if (argMap.containsKey("verbose")) Utils.verbose = ("true".equals(argMap.get("verbose")));
		
		
		return argMap;
	}
	
	public static void runProxy () {
		ServerSocketFactory factory = SSLServerSocketFactory.getDefault();
			
		try (ServerSocket serversocket = factory.createServerSocket(_LISTENING_PORT);) {
			loginfo("Proxy listening on port "+serversocket.getLocalPort()+"...");
			
			while (true) {
				SjProxy proxyserver = new SjProxy(serversocket.accept());
				loginfo("Connection opened from port "+ proxyserver.client.getPort() +" to port " + serversocket.getLocalPort());
				
				Thread clientThread = new Thread(proxyserver);
				clientThread.start();
			}
			
		} catch (IOException e) {
			e.printStackTrace(); 
		}
	}
	
	@Override
	public void run() {		
		String incomingClientMessageLine = null;
		
		try (InputStreamReader inputStreamReader = new InputStreamReader(client.getInputStream());
			 BufferedReader streamFromClient = new BufferedReader(inputStreamReader);
			 PrintWriter streamToClient = new PrintWriter(client.getOutputStream(),true)) {
			
			String[] clientRequest = readRequest(streamFromClient);
			incomingClientMessageLine = clientRequest[0];
			if (incomingClientMessageLine == null) return;
			logtrace("[from client] "+incomingClientMessageLine.replace('\r', '-').replace('\n','_'));
			
			final Pattern CONNECT_PATTERN = Pattern.compile("CONNECT (.+):(.+) HTTP/(1\\.[01])", Pattern.CASE_INSENSITIVE);
			Matcher connectRequest = CONNECT_PATTERN.matcher(incomingClientMessageLine.trim());
			
			//1. client -> proxy msg should be a CONNECT request to an allowed destination
			String httpreturn = validateConnectRequest(incomingClientMessageLine, connectRequest);
			if (!_HTTP_200_CONNECTION_OK.equals(httpreturn)) {
				streamToClient.write("HTTP/" + connectRequest.group(3) + " "+httpreturn+"\r\n");
				streamToClient.write("Proxy-agent: SJProxy/1.0\r\n");
				streamToClient.write("\r\n");
				streamToClient.flush();
				return;
			}				
			//2. client -> proxy might have additional headers
			for (int i=1;i<clientRequest.length;i++) {
				logtrace("[from client, header] ("+i+") "+clientRequest[i].replace('\r', '-').replace('\n','_'));
				// (placeholder in case I want to do something with these.)
			}
							
			//3. get target server:port and open a socket. if successful, send HTTP 200 back to client.			
			final Socket target = new Socket();
			attemptConnectionToTarget(target, connectRequest, streamToClient);
			
							
			//4. Now we passthru data from client->target and vice versa
			
			//4a -> spin up a new thread for passing data from target -> client
			Thread targetToClient = new Thread() {
				@Override
				public void run() {
					try {
						passthru(target, client);
					}
					catch (IOException e) {
						logerror("[from "+target.getRemoteSocketAddress()+"] "+e.getMessage());
						e.printStackTrace();
					}
				}
			};
			
			targetToClient.start();
			
			//4b. we'll pass data from client -> target on this existing thread.
			passthru(client, target);
			
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private String[] readRequest(BufferedReader streamReader) throws IOException {
		List<String> requestLines = new ArrayList<String>();
		ByteArrayOutputStream byteArray = new ByteArrayOutputStream();
		
		int msgChar = streamReader.read();
		int lastChar = -1;
		
		boolean EOM = false, EOL = false;
				
		while (msgChar != -1 && !EOM) {
			EOM = (lastChar == '\r' && msgChar == '\n');
			EOL = (msgChar == '\n');
			byteArray.write(msgChar);
			lastChar = msgChar;
			
			if (EOL) {
				requestLines.add(byteArray.toString());
				byteArray.reset();
			}
			
			msgChar = streamReader.read();
		}
		
		return requestLines.toArray(new String[requestLines.size()]);
	}
	
	private String validateConnectRequest (String clientMessage, Matcher connectRequest) {
		
		boolean isValidConnectRequest = connectRequest.matches();
		if (!isValidConnectRequest) {
			logerror("CONNECT expected, instead received : "+clientMessage);
			return _HTTP_405_NOT_ALLOWED;
		}
		
		String hostname = connectRequest.group(1);
		int portnumber = Integer.parseInt(connectRequest.group(2));
		if (!isAllowed(hostname,portnumber)) {
			logerror(hostname+":"+portnumber+" not allowed. Returning "+_HTTP_403_FORBIDDEN+" to "+client.getRemoteSocketAddress());
			return _HTTP_403_FORBIDDEN;
		}
		
		return _HTTP_200_CONNECTION_OK;
		
	}
	
	private void passthru (Socket source, Socket destination) throws IOException {
		byte[] msgBytes = new byte[4096]; //read 4k at a time. too small?
		int bytesRead = 0;
		
		while (bytesRead >= 0 ) {
			logtrace("[from "+source.getRemoteSocketAddress()+"] Waiting for bytes...");
			
			bytesRead = source.getInputStream().read(msgBytes); //block until there is something to be read

			if (bytesRead != -1) {
				logtrace("[from "+source.getRemoteSocketAddress()+"] Passthru attempt "+msgBytes.toString() );	
			}
			destination.getOutputStream().write(msgBytes);
			destination.getOutputStream().flush();				
			
			if (bytesRead == -1) { 
				logtrace("[from "+source.getRemoteSocketAddress()+"] Stream complete");
			}
		}
	}
	
	private Socket attemptConnectionToTarget(Socket target, Matcher connectRequest, PrintWriter streamToClient) {
		try {
			final SocketAddress targetAddress = new InetSocketAddress(connectRequest.group(1),Integer.parseInt(connectRequest.group(2)));
			target.connect(targetAddress); 
			
			logtrace("[to client] HTTP/" + connectRequest.group(3) + " " + _HTTP_200_CONNECTION_OK);
			logtrace("[to client] Proxy-agent: SJProxy/1.0");
			streamToClient.write("HTTP/" + connectRequest.group(3) + " " + _HTTP_200_CONNECTION_OK+"\n");
			streamToClient.write("Proxy-agent: SJProxy/1.0\n");
			streamToClient.write("\r\n");
			streamToClient.flush();
			
            
		} catch (IOException | NumberFormatException e) {
			logerror("Exception on connection or response attempt: "+e.getMessage());
			streamToClient.write("HTTP/" + connectRequest.group(3) + " "+_HTTP_502_BAD_GATEWAY+"\r\n");
			streamToClient.write("Proxy-agent: SJProxy/1.0\r\n");
			streamToClient.write("\r\n");
			streamToClient.flush();
		}
		return target;
	}
}
