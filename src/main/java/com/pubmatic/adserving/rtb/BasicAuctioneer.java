package com.pubmatic.adserving.rtb;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.nio.DefaultHttpClientIODispatch;
import org.apache.http.impl.nio.pool.BasicNIOConnPool;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.nio.protocol.BasicAsyncRequestProducer;
import org.apache.http.nio.protocol.BasicAsyncResponseConsumer;
import org.apache.http.nio.protocol.HttpAsyncRequestExecutor;
import org.apache.http.nio.protocol.HttpAsyncRequester;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.params.SyncBasicHttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.ImmutableHttpProcessor;
import org.apache.http.protocol.RequestConnControl;
import org.apache.http.protocol.RequestContent;
import org.apache.http.protocol.RequestExpectContinue;
import org.apache.http.protocol.RequestTargetHost;
import org.apache.http.protocol.RequestUserAgent;

/**
 * Asynchronous HTTP/1.1 RTB Auctioneer.
 */
public class BasicAuctioneer {

    private static final int DEFAULT_BIDDERS = 20;
    private static final int MAX_WAIT_IN_MS = 100;
    private static final double SIMULATOR_MAX_DELAY_IN_MS = 120.0; 
    
    private int biddersN = DEFAULT_BIDDERS;
        
	// Create client-side I/O reactor
    final private ConnectingIOReactor ioReactor;
    private BasicNIOConnPool pool = null;
    private HttpAsyncRequester requester = null;

    public BasicAuctioneer() throws Exception {
    	ioReactor = new DefaultConnectingIOReactor();
    	initialize();
    }

    public void initialize() {
        // HTTP parameters for the client
        HttpParams params = new SyncBasicHttpParams();
        params
            .setIntParameter(CoreConnectionPNames.SO_TIMEOUT, MAX_WAIT_IN_MS)
            .setIntParameter(CoreConnectionPNames.CONNECTION_TIMEOUT, MAX_WAIT_IN_MS)
            .setIntParameter(CoreConnectionPNames.SOCKET_BUFFER_SIZE, 8 * 1024)
            .setParameter(CoreProtocolPNames.USER_AGENT, "PubMatic-RTB-2.0-Auctioneer/1.1");
        
        // Create HTTP protocol processing chain
        HttpProcessor httpproc = new ImmutableHttpProcessor(new HttpRequestInterceptor[] {
                // Use standard client-side protocol interceptors
                new RequestContent(),
                new RequestTargetHost(),
                new RequestConnControl(),
                new RequestUserAgent(),
                new RequestExpectContinue()});
        // Create client-side HTTP protocol handler
        HttpAsyncRequestExecutor protocolHandler = new HttpAsyncRequestExecutor();
        // Create client-side I/O event dispatch
        final IOEventDispatch ioEventDispatch = new DefaultHttpClientIODispatch(protocolHandler, params);
        // Create HTTP connection pool
        pool = new BasicNIOConnPool(ioReactor, params);
        // Limit total number of connections
        pool.setDefaultMaxPerRoute(200);
        pool.setMaxTotal(200);
        // Run the I/O reactor in a separate thread
        Thread t = new Thread(new Runnable() {

            public void run() {
                try {
                    // Ready to go!
                    ioReactor.execute(ioEventDispatch);
                } catch (InterruptedIOException ex) {
                    System.err.println("Interrupted");
                } catch (IOException e) {
                    System.err.println("I/O error: " + e.getMessage());
                }
                System.out.println("Shutdown");
            }

        });
        // Start the client thread
        t.start();
        // Create HTTP requester
        requester = new HttpAsyncRequester(
                httpproc, new DefaultConnectionReuseStrategy(), params);
    }
    
    public void makeRequest(final BidRequest bidRequest, final CountDownLatch latch) {
    	
        BasicHttpRequest request = new BasicHttpRequest("GET", "/"+bidRequest.getPath()+"?id="+bidRequest.getId()+"&maxwait="+SIMULATOR_MAX_DELAY_IN_MS);
        requester.execute(
                new BasicAsyncRequestProducer(bidRequest.getTarget(), request),
                new BasicAsyncResponseConsumer(),
                pool,
                new BasicHttpContext(),
                // Handle HTTP response from a callback
                new FutureCallback<HttpResponse>() {

            public void completed(final HttpResponse response) {
                latch.countDown();
                System.out.println(bidRequest.getId() +" "+ bidRequest.getTarget() + "->" + response.getStatusLine());
                try {
                	HttpEntity entity = response.getEntity();
                	InputStream inputStream = entity.getContent();
                	
                	String utf8string = IOUtils.toString(inputStream, "UTF-8");
                	                	
                	System.out.println("\tResponse from "+bidRequest.getTarget() + "->" + utf8string);
                } catch (Exception e) {}
                
            }

            public void failed(final Exception ex) {
                latch.countDown();
                System.out.println(bidRequest.getTarget() + "->" + ex);
            }

            public void cancelled() {
                latch.countDown();
                System.out.println(bidRequest.getTarget() + " cancelled");
            }
        });
        
        System.out.println("Sent request for "+bidRequest.getPath()+" "+bidRequest.getId());
    }
    
    class BidRequest {
    	private final HttpHost target;
		private final String path;
		private final String id;
    	
		public BidRequest(HttpHost target, String path, String id) {
			this.target = target;
			this.path = path;
			this.id = id;
		}

    	public HttpHost getTarget() {
			return target;
		}

		public String getPath() {
			return path;
		}
		
		public String getId() {
			return id;
		}
    }
    
    private BidRequest[] bidRequests = null;
    
    public void prepareRequest() {
    	// Execute HTTP GETs to the following hosts and
    	bidRequests = new BidRequest[getBiddersN()];
    	for (int i=0; i<bidRequests.length; i++) {
    		bidRequests[i] = new BidRequest(new HttpHost("localhost", 8080, "http"), "dsp-simulator/bidder", new Integer(i).toString());
//    		bidRequests[i] = new BidRequest(new HttpHost("localhost", 80, "http"), "bidder.html", new Integer(i).toString()); 
    	}
    }
    
    public void setBiddersN(int biddersN) {
    	this.biddersN = biddersN;
    }
    
    public int getBiddersN() {
    	return biddersN;
    }
    
    public void makeRequest() throws InterruptedException, IOException {
        
        final CountDownLatch latch = new CountDownLatch(bidRequests.length);
        for (final BidRequest request: bidRequests) {
        	makeRequest(request, latch);
        }
        
//        latch.await();
        latch.await(MAX_WAIT_IN_MS, TimeUnit.MILLISECONDS);
        
    }
    
    public void shutdown() {
        System.out.println("Shutting down I/O reactor");
        try { ioReactor.shutdown(); } catch (Exception e) {}
        System.out.println("Done");
	}



}