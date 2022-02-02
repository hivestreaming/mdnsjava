package net.posicks.mDNS;

import static org.junit.Assert.fail;

import java.net.InetAddress;

import org.junit.Test;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;

import net.posick.mDNS.Browse;
import net.posick.mDNS.DNSSDListener;
import net.posick.mDNS.Lookup;
import net.posick.mDNS.Lookup.RecordListener;
import net.posick.mDNS.MulticastDNSService;
import net.posick.mDNS.ServiceInstance;
import net.posick.mDNS.ServiceName;

/**
 * The following are examples of mdnsjava API usage.
 * <p>
 * All domain names in mdnsjava are considered to be relative to the domain search path unless they end with a period ..
 * <p>
 * For example:
 * <p>
 * <ul>
 * <li>The domain name posick.net. will be treated as an absolute domain name, looking up records in the posick.net domain.</li>
 * <li>The domain name posick.net will be treated as a relative domain name and will be prepended to each domain listed
 * in the DNS search path, ex, posick.net.local., posick.net.posick.net., etc...</li>
 * </ul>
 *
 * @author stefaneicher
 */
public class ApiUsageExamplesTest
{
    
    private static final String SERVICE_NAME = "Test._org.smpte.st2071.device:device_v1.0._sub._mdc._tcp";


    /**
     * Lookup the registered Browse and Registration Domains RFC 6263 Section 11 using the default DNS and mDNS search paths.
     */
    @Test
    public void Lookup_the_registered_Browse_and_Registration_Domains()
    throws Exception
    {
        Lookup lookup = new Lookup(MulticastDNSService.DEFAULT_REGISTRATION_DOMAIN_NAME, MulticastDNSService.REGISTRATION_DOMAIN_NAME, MulticastDNSService.DEFAULT_BROWSE_DOMAIN_NAME, MulticastDNSService.BROWSE_DOMAIN_NAME, MulticastDNSService.LEGACY_BROWSE_DOMAIN_NAME);
        
        Lookup.Domain[] domains = lookup.lookupDomains();
        for (Lookup.Domain domain : domains)
        {
            System.out.println(domain);
        }
        lookup.close();
        
    }
    
    
    /**
     * Lookup (Resolve) Services (one shot synchronous).
     */
    @Test
    public void lookup_services()
    throws Exception
    {
        ServiceName serviceName = new ServiceName(SERVICE_NAME + ".local.");
        
        Lookup lookup = null;
        try
        {
            lookup = new Lookup(serviceName);
            ServiceInstance[] services = lookup.lookupServices();
            for (ServiceInstance service : services)
            {
                System.out.println(service);
            }
        } finally
        {
            if (lookup != null)
            {
                lookup.close();
            }
        }
    }
    
    
    /**
     * Asynchronously Browse for Registered Services.
     * <p>
     * The DNSSDListener receives service registration and removal events as they are received.
     * To locate services that were registered before starting the browse operation us the Lookup (Resolve)
     * Services process described above.
     */
    @Test
    public void Asynchronously_Browse_for_Registered_Services()
    throws Exception
    {
        String[] serviceTypes = new String[] {"_http._tcp.", // Web pages
                                              "_printer._sub._http._tcp", // Printer configuration web pages
                                              "_org.smpte.st2071.device:device_v1.0._sub._mdc._tcp", // SMPTE ST2071 Devices
                                              "_org.smpte.st2071.service:service_v1.0._sub._mdc._tcp" // SMPTE ST2071 Services
        };
        
        MulticastDNSService service = new MulticastDNSService();
        try
        {
            service.startServiceDiscovery(new Browse(serviceTypes), new DNSSDListener()
            {
                public void serviceDiscovered(Object id, ServiceInstance service)
                {
                    System.out.println("Service Discovered - " + service);
                }
                
                
                public void serviceRemoved(Object id, ServiceInstance service)
                {
                    System.out.println("Service Removed - " + service);
                }
                
                
                public void handleException(Object id, Exception e)
                {
                    System.err.println("Exception: " + e.getMessage());
                    e.printStackTrace(System.err);
                }
                
                
                public void receiveMessage(Object id, Message message)
                {
                    System.out.println("Message Received for Object: " + id + " - " + message);
                }
            });
            Thread.sleep(5000);
        } finally
        {
            service.close();
        }
    }
    
    
    /**
     * Lookup (Resolve) a Records Synchronously.
     */
    @Test
    public void Lookup_a_Records_Synchronously()
    throws Exception
    {
        Lookup lookup = null;
        try
        {
            lookup = new Lookup(SERVICE_NAME, Type.ANY, DClass.IN);
            Record[] records = lookup.lookupRecords();
            for (Record record : records)
            {
                System.out.println(record);
            }
        } finally
        {
            if (lookup != null)
            {
                lookup.close();
            }
        }
    }
    
    
    @Test
    public void Lookup_a_Records_Asynchronously()
    throws Exception
    {
        Lookup lookup = null;
        try
        {
            lookup = new Lookup(SERVICE_NAME, Type.ANY, DClass.IN);
            lookup.lookupRecordsAsync(new RecordListener()
            {
                public void receiveRecord(Object id, Record record)
                {
                    System.out.println("Record Found - " + record);
                }
                
                
                public void handleException(Object id, Exception e)
                {
                    System.err.println("Exception: " + e.getMessage());
                    e.printStackTrace(System.err);
                }
            });
            Thread.sleep(1000);
        } finally
        {
            if (lookup != null)
            {
                lookup.close();
            }
        }
    }
    
    
    /**
     * Registering a Services
     */
    @Test
    public void Register_and_Unregister_a_Service()
    throws Exception
    {
        ServiceName serviceName = new ServiceName(SERVICE_NAME + ".local.");
        Name hostname = new Name("testhost.local.");
        int port = 8080;
        InetAddress[] addresses = new InetAddress[] {InetAddress.getLocalHost()};
        String[] txtValues = new String[] {""};
        
        MulticastDNSService mDNSService = new MulticastDNSService();
        ServiceInstance service = new ServiceInstance(serviceName, 0, 0, port, hostname, addresses, txtValues);
        ServiceInstance registeredService = mDNSService.register(service);
        if (registeredService != null)
        {
            System.out.println("Services Successfully Registered: \n\t" + registeredService);
        } else
        {
            System.err.println("Services Registration Failed!");
            fail("Services Registration Failed!");
        }
        
        if (mDNSService.unregister(serviceName))
        {
            System.out.println("Services Successfully Unregistered: \n\t" + serviceName);
        } else
        {
            System.err.println("Services Unregistration Failed!");
            fail("Services Unregistration Failed!");
        }
        mDNSService.close();
    }
}
