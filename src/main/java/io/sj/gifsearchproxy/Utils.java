package io.sj.gifsearchproxy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Utils {
	
	static boolean verbose = false; //default value, can be overridden by cmd line option
	
	private static List<String> allowed_hosts;
	
	private static void log (PrintStream stream, String msg) {
		stream.println("["+LocalDateTime.now()+"] [" +Thread.currentThread().getId()+ "] " +msg);
	}
	static void logtrace (String msg) {
		if (verbose) log(System.out,msg);
	}
	static void loginfo (String msg) {
		log(System.out,msg);
	}
	static void logerror (String msg) {
		log(System.err,msg);
	}
	static void logStartupDetails(Map<String,String> options) {
		if (!verbose) return;
		RuntimeMXBean mxBean = ManagementFactory.getRuntimeMXBean();
		List<String> jvmArgs = mxBean.getInputArguments();
		
		System.out.println("-----------------");
		System.out.println(System.getProperty("java.home"));		
		
		for (int i=0;i<jvmArgs.size();i++) {
			System.out.println("arg "+i+": "+jvmArgs.get(i));
		}
		
		options.forEach((key,value) -> System.out.println(key+": "+value));
		System.out.println("-----------------");
	}
	static boolean isAllowed(String hostname, int port) {		
		if (allowed_hosts == null) {
			loadAllowedHostsFile();
		}
		
		return allowed_hosts.contains(hostname+":"+port);
	}
	private static void loadAllowedHostsFile() {
		ClassLoader classLoader = Utils.class.getClassLoader();
		try (InputStream inputStream = classLoader.getResourceAsStream("hosts.allow");
				InputStreamReader isr = new InputStreamReader(inputStream);
				BufferedReader reader = new BufferedReader(isr)) {
			
			allowed_hosts = reader.lines().collect(Collectors.toList());
		} catch (IOException e) {
			logerror("Exception while loading allowed hosts file: "+e.getMessage());
		}
	}
}
