package org.archive.wayback.accesscontrol.robotstxt.redis;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.archive.wayback.accesscontrol.robotstxt.redis.RedisRobotsLogic.KeyRedisValue;
import org.archive.wayback.accesscontrol.robotstxt.redis.RedisRobotsLogic.RedisValue;
import org.archive.wayback.core.Resource;
import org.archive.wayback.exception.LiveDocumentNotAvailableException;
import org.archive.wayback.exception.LiveWebCacheUnavailableException;
import org.archive.wayback.exception.LiveWebTimeoutException;
import org.archive.wayback.webapp.PerformanceLogger;

public class RedisRobotsCache extends LiveWebProxyCache {

	private final static Logger LOGGER = Logger
			.getLogger(RedisRobotsCache.class.getName());
	
	/* ROBOTS TTL PARAMS */
	
	final static int ONE_DAY = 60 * 60 * 24;

	private int totalTTL = ONE_DAY * 10;
	private int refreshTTL = ONE_DAY;

	private int notAvailTotalTTL = ONE_DAY * 2;
	private int notAvailRefreshTTL = ONE_DAY / 2;
	
	final static String ROBOTS_TOKEN_EMPTY = "0_ROBOTS_EMPTY";
	
	final static String ROBOTS_TOKEN_ERROR = "0_ROBOTS_ERROR-";
	final static String ROBOTS_TOKEN_ERROR_UNKNOWN = "0_ROBOTS_ERROR-0";
	
	final static String UPDATE_QUEUE_KEY = "robots_update_queue";
	final static int MAX_UPDATE_QUEUE_SIZE = 50000;
	
	/* THREAD WORKER SETTINGS */

	private Map<String, RobotsContext> activeContexts;
	
	/* REDIS */
	private RedisRobotsLogic redisCmds;
		
	public void setRedisConnMan(RedisConnectionManager redisConn) {
		this.redisCmds = new RedisRobotsLogic(redisConn);
	}

	@Override
	public void init() {
		super.init();
		activeContexts = new HashMap<String, RobotsContext>();
	}
	
	@Override
	public Resource getCachedResource(URL urlURL, long maxCacheMS,
				boolean bUseOlder) throws LiveDocumentNotAvailableException,
				LiveWebCacheUnavailableException, LiveWebTimeoutException,
				IOException {
		
		String url = urlURL.toExternalForm();
		
		RedisValue value = null;
		
		try {
			value = redisCmds.getValue(url);
		} catch (LiveWebCacheUnavailableException lw) {
			value = null;
		}
			
		if (value == null) {
			RobotsContext context = doSyncUpdate(url, null, true, true);
											
			if ((context == null) || !context.isValid()) {
				throw new LiveDocumentNotAvailableException("Error Loading Live Robots");	
			}
			
			return new RobotsTxtResource(context.getNewRobots());
			
		} else {
			
			if (isExpired(value, url, 0)) {	
				redisCmds.pushKey(UPDATE_QUEUE_KEY, url, MAX_UPDATE_QUEUE_SIZE);
			}
			
			String currentRobots = value.value;
			
			if (currentRobots.startsWith(ROBOTS_TOKEN_ERROR)) {
				throw new LiveDocumentNotAvailableException("Robots Error: " + currentRobots);	
			} else if (value.equals(ROBOTS_TOKEN_EMPTY)) {
				currentRobots = "";
			}
			
			return new RobotsTxtResource(currentRobots);	
		}
	}
	
	public RobotsContext forceUpdate(String url, int minUpdateTime)
	{
		String current = null;
		
		try {
			RedisValue value = redisCmds.getValue(url);
			
			// Just in case, avoid too many updates
			if ((minUpdateTime > 0) && (value != null) && !isExpired(value, url, minUpdateTime)) {
				return new RobotsContext(url, current, false, true);
			}
			
			current = (value != null ? value.value : null);
		} catch (LiveWebCacheUnavailableException lw) {
			current = lw.toString();
		}
		
		RobotsContext context = doSyncUpdate(url, current, false, true);
		LOGGER.info("Force updated: " + url);
		return context;
	}
	
	protected boolean isValidRobots(String value) {
		return !value.startsWith(ROBOTS_TOKEN_ERROR) && !value.equals(ROBOTS_TOKEN_EMPTY);
	}
			
	public boolean isExpired(RedisValue value, String url, int customRefreshTime) {
				
		int maxTime, refreshTime;
		
		boolean isFailedError = value.value.startsWith(ROBOTS_TOKEN_ERROR);
		
		if (isFailedError) {
			String code = value.value.substring(ROBOTS_TOKEN_ERROR.length());
			isFailedError = RobotsContext.isFailedError(code);
		}
		
		if (isFailedError) {
			maxTime = notAvailTotalTTL;
			refreshTime = notAvailRefreshTTL;
		} else {
			maxTime = totalTTL;
			refreshTime = refreshTTL;
		}
		
		if (customRefreshTime > 0) {
			refreshTime = customRefreshTime;
		}
		
		if ((maxTime - value.ttl) >= refreshTime) {
			LOGGER.info("Queue for robot refresh: "
					+ (maxTime - value.ttl) + ">=" + refreshTime + " " + url);
			
			return true;
		}
		
		return false;
	}
	
	protected void processRedisUpdateQueue()
	{
		int errorCounter = 0;
		
		int maxErrorThresh = 10;
		int errorSleepTime = 10000;
		
		int maxQueued = 500;
		int currQSize = 0;
		
		try {			
			
			while (true) {
				if (errorCounter >= maxErrorThresh) {
					LOGGER.warning(errorCounter + " Redis ERRORS! Sleeping for " + errorSleepTime);
					Thread.sleep(errorSleepTime);
				}
				
				synchronized(activeContexts) {
					currQSize = activeContexts.size();
				}
									
				if (currQSize >= maxQueued) {
					Thread.sleep(100);
					continue;
				} else {
					Thread.sleep(0);
				}
				
				KeyRedisValue value = null;
				
				long startTime = System.currentTimeMillis();
								
				try {
					value = redisCmds.popKeyAndGet(UPDATE_QUEUE_KEY);
					errorCounter = 0;
				} catch (LiveWebCacheUnavailableException e) {
					errorCounter++;
				} catch (Exception exc) {
					errorCounter = maxErrorThresh;
					LOGGER.log(Level.SEVERE, "REDIS SEVERE", exc);
				} finally {
					PerformanceLogger.noteElapsed("PopKeyAndGet", System.currentTimeMillis() - startTime);
				}
				
				if (value == null) {
					continue;
				}
				
				String url = value.key;
				
				if (!isExpired(value, url, 0)) {
					continue;
				}
				
				RobotsContext context = null;
				
				synchronized(activeContexts) {
					if (activeContexts.containsKey(url)) {
						continue;
					}
					context = new RobotsContext(url, value.value, true, true);
					activeContexts.put(url, context);
				}
							
				refreshService.execute(new AsyncLoadAndUpdate(context));

			}
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, "UPDATER SEVERE", e);
		} finally {
			shutdown();
		}
	}
	
	private RobotsContext doSyncUpdate(String url, String current, boolean cacheFails, boolean canceleable)
	{
		RobotsContext context = null;
		boolean toLoad = false;
		
		int numUrls = 0;
		
		synchronized(activeContexts) {
			context = activeContexts.get(url);
			if (context == null) {
				context = new RobotsContext(url, current, cacheFails, false);
				activeContexts.put(url, context);
				toLoad = true;
			}
			numUrls = activeContexts.size();
		}
				
		if (toLoad) {		
			Future<RobotsContext> futureResponse = 
				refreshService.submit(new AsyncLoadAndUpdate(context), context);
						
			try {
				context = futureResponse.get(responseTimeoutMS, TimeUnit.MILLISECONDS);
				
			} catch (Exception e) {
				LOGGER.info("INTERRUPTED: " + url + " " + e);
				
				if (canceleable)	{	
					futureResponse.cancel(true);
				}
			
			} finally {				
				context.latch.countDown();
				
				synchronized(activeContexts) {
					activeContexts.remove(url);
				}
			}
		} else {
			
			try {
				LOGGER.info("WAITING FOR: " + url + " -- # URLS: " + numUrls);
				
				if (!context.latch.await(responseTimeoutMS, TimeUnit.MILLISECONDS)) {
					LOGGER.info("WAIT FOR " + url + " timed out!");
				}
				
			} catch (InterruptedException e) {
				LOGGER.info("INTERRUPT FOR " + url);
			}
		}
		
		return context;
	}
		
	private void updateCache(final RobotsContext context) {		
		String contents = null;
		
		String newRedisValue = null;
		int newTTL = 0;
		boolean ttlOnly = false;
		
		if (context.isValid()) {
			contents = context.getNewRobots();
			newTTL = totalTTL;
			
			if (contents.isEmpty()) {
				newRedisValue = ROBOTS_TOKEN_EMPTY;
			} else if (contents.length() > RobotsContext.MAX_ROBOTS_SIZE) {
				newRedisValue = contents.substring(0, RobotsContext.MAX_ROBOTS_SIZE);
			} else {
				newRedisValue = contents;
			}
			
		} else {			
			if (context.isFailedError()) {
				newTTL = notAvailTotalTTL;
				// Only Cacheing successful lookups
				if (!context.cacheFails) {
					return;
				}
			} else {
				newTTL = totalTTL;
			}
			
			newRedisValue = ROBOTS_TOKEN_ERROR + context.getStatus();
		}
		
		String currentValue = context.current;		
		
		if (currentValue != null) {
			if (currentValue.equals(newRedisValue)) {
				ttlOnly = true;
			}
			
			// Don't override a valid robots with a timeout error
			if (!context.isRedirectStatus() && !isValidRobots(newRedisValue) && isValidRobots(currentValue)) {
				newTTL = totalTTL;
				ttlOnly = true;
				LOGGER.info("REFRESH ERROR: " + context.getStatus() + " - Keeping same robots for " + context.url);
			}
		}
				
		final RedisValue value = new RedisValue((ttlOnly ? null : newRedisValue), newTTL);
		redisCmds.updateValue(context.url, value);
	}
		
	public void processAsyncUpdate(final RobotsContext context)
	{
		context.startTime = System.currentTimeMillis();
		
		try {									
			connMan.loadRobots(context, context.url, userAgent);
			
			updateCache(context);
			
			String pingStatus;
			long startTimePingProxy = System.currentTimeMillis();
			
			if (connMan.pingProxyLive(context.url)) {
				pingStatus = "PingProxySuccess";
			} else {
				pingStatus = "PingProxyFailure";
			}
			
			PerformanceLogger.noteElapsed(pingStatus, System.currentTimeMillis() - startTimePingProxy, context.url + " ");
			
		} catch (InterruptedException e) {
			// Interrupt Update
		} finally {
			if (context.isSingleWait()) {
				synchronized(activeContexts) {
					activeContexts.remove(context.url);
				}
			}
			
			PerformanceLogger.noteElapsed("AsyncLoadAndUpdate", System.currentTimeMillis() - context.startTime, context.url);
		}		
	}
	
	class AsyncLoadAndUpdate implements Runnable
	{
		RobotsContext context;
		
		AsyncLoadAndUpdate(RobotsContext context)
		{
			this.context = context;
		}
		
		@Override
		public void run()
		{
			processAsyncUpdate(context);
		}
	}

	@Override
	public void shutdown() {
		super.shutdown();
		
		try {
			refreshService.awaitTermination(10000, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {

		}
		
		if (redisCmds != null) {
			redisCmds.close();
			redisCmds = null;
		}
		
		if (connMan != null) {
			connMan.close();
			connMan = null;
		}
	}
	
	@Override
	protected void appendLogInfo(PrintWriter info)
	{
		super.appendLogInfo(info);
		
		info.println("  Num Threads: " + Thread.activeCount());
                
        synchronized(activeContexts) {
            info.println("  Active URLS: " + activeContexts.size());
            
//        	for (RobotsContext context : activeContexts.values()) {
//        		long age = System.currentTimeMillis() - context.created;
//        		info.println("   " + context.url + " " + age);
//        	}
        }
        
        redisCmds.appendLogInfo(info);
	}
		
	public static void main(String args[])
	{
		String redisHost = "localhost";
		String redisPassword = null;
		String recProxy = null;
		String userAgent = null;
		int redisPort = 6379;
		
		int maxPerRouteConnections = 0;
		int maxConnections = 0;
		int maxCoreUpdateThreads = 0;
		int maxRedisConn = 0;
		
		Iterator<String> paramsIter = Arrays.asList(args).iterator();
		
		while (paramsIter.hasNext()) {
			String flag = paramsIter.next();
			
			if (!paramsIter.hasNext()) {
				break;
			}
			
			if (flag.equals("-h")) {
				redisHost = paramsIter.next();
			} else if (flag.equals("-p")) {
				redisPort = Integer.parseInt(paramsIter.next());		
			} else if (flag.equals("-a")) {
				redisPassword = paramsIter.next();
			} else if (flag.equals("-r")) {
				recProxy = paramsIter.next();
			} else if (flag.equals("-u")) {
				userAgent = paramsIter.next();
			} else if (flag.equals("-max_conn")) {
				maxConnections = Integer.parseInt(paramsIter.next());
			} else if (flag.equals("-max_route_conn")) {
				maxPerRouteConnections = Integer.parseInt(paramsIter.next());
			} else if (flag.equals("-max_thread")) {
				maxCoreUpdateThreads = Integer.parseInt(paramsIter.next());
			} else if (flag.equals("-max_redis")) {
				maxRedisConn = Integer.parseInt(paramsIter.next());
			}
		}
		
		RedisConnectionManager redisMan = new RedisConnectionManager();
		redisMan.setHost(redisHost);
		redisMan.setPort(redisPort);
		redisMan.setPassword(redisPassword);
		if (maxRedisConn != 0) {
			redisMan.setConnections(maxRedisConn);
		}
		redisMan.init();
				
		LOGGER.info("Redis Updater: " + redisHost + ":" + redisPort);
		
		ApacheHttpConnMan httpManager = new ApacheHttpConnMan();
		
		if (recProxy != null) {
			httpManager.setProxyHostPort(recProxy);	
		}
		
		if (maxConnections != 0) {		
			httpManager.setMaxConnections(maxConnections);
		}
		
		if (maxPerRouteConnections != 0) {
			httpManager.setMaxPerRouteConnections(maxPerRouteConnections);
		}
		
		httpManager.init();
		
		RedisRobotsCache cache = new RedisRobotsCache();
		cache.setHttpConnMan(httpManager);
		cache.setRedisConnMan(redisMan);
		cache.setUserAgent(userAgent);
		
		if (maxCoreUpdateThreads != 0) {
			cache.setMaxCoreUpdateThreads(maxCoreUpdateThreads);
		}
		
		cache.init();
		cache.processRedisUpdateQueue();
	}
}
