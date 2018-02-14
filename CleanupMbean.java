import javax.management.remote.*;
import javax.management.*;
import java.util.*;
import java.lang.*;
import java.io.*;
import java.net.*;
import com.sun.net.httpserver.*;

public class CleanupMbean {
    
    public static void main(String[] args) {
        try {
            cleanup(args[0], args[1]);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    static void cleanup(String serverName, String port) {
        try {
            JMXServiceURL u = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://" + serverName + ":" + port +  "/jmxrmi");
            System.out.println("URL: "+u+", connecting");

            JMXConnector c = JMXConnectorFactory.connect(u);
            
            System.out.println("Connected: " + c.getConnectionId());
            
            MBeanServerConnection m = c.getMBeanServerConnection();
            
            for (ObjectInstance x : m.queryMBeans(null, null)) {
                System.out.println("Checking " + x.getObjectName().toString());
                if (x.getObjectName().toString().startsWith("DefaultDomain:type=MLet")
                    ||
                   (x.getObjectName().toString().startsWith("MLetCompromise"))
                    ||
                   (x.getObjectName().toString().startsWith("MLet"))
                    ){
                    System.out.println("Removing" + x.getObjectName().toString());
                    m.unregisterMBean(x.getObjectName());
                }
            }
            System.out.println("Exiting after cleanup");
            System.exit(0); 
    } catch (Exception e) {
            e.printStackTrace();
    }
  }
}
