package net.osmand.plus.resources;


import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import net.osmand.PlatformUtil;
import net.osmand.ResultMatcher;
import net.osmand.data.QuadRect;
import net.osmand.data.RotatedTileBox;
import net.osmand.data.TransportStop;
import net.osmand.map.ITileSource;
import net.osmand.map.MapTileDownloader.DownloadRequest;
import net.osmand.map.MapTileDownloader.IMapDownloaderCallback;
import net.osmand.plus.BusyIndicator;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;

import android.os.Handler;
import android.os.HandlerThread;

/**
 * Thread to load map objects (POI, transport stops )async
 */
public class AsyncLoadingThread extends Thread {
	
	public static final int LIMIT_TRANSPORT = 200;
	
	private static final Log log = PlatformUtil.getLog(AsyncLoadingThread.class); 
	
	private Handler asyncLoadingTransport;
	
	Stack<Object> requests = new Stack<Object>();
	TransportLoadRequest transportLoadRequest = null;
	
	
	private final ResourceManager resourceManger;
	

	public AsyncLoadingThread(ResourceManager resourceManger) {
		super("Loader map objects (synchronizer)"); //$NON-NLS-1$
		this.resourceManger = resourceManger;
	}
	
	
	
	private void startTransportLoadingThread() {
		HandlerThread h = new HandlerThread("Loading transport");
		h.start();
		asyncLoadingTransport = new Handler(h.getLooper());
	}

	private int calculateProgressStatus() {
		int progress = 0;
		if (resourceManger.getMapTileDownloader() != null && resourceManger.getMapTileDownloader().isSomethingBeingDownloaded()) {
			progress = BusyIndicator.STATUS_GREEN;
		} else if (resourceManger.getContext().getRoutingHelper().isRouteBeingCalculated()) {
			progress = BusyIndicator.STATUS_ORANGE;
		} else if (resourceManger.isSearchAmenitiesInProgress()) {
			progress = BusyIndicator.STATUS_BLACK;
		} else if (!requests.isEmpty()) {
			progress = BusyIndicator.STATUS_BLACK;
		} else if (transportLoadRequest != null && transportLoadRequest.isRunning()) {
			progress = BusyIndicator.STATUS_BLACK;
		}
		return progress;
	}

	@Override
	public void run() {
		while (true) {//死循环监测堆栈requests
			try {
				boolean tileLoaded = false;
				boolean amenityLoaded = false;
				boolean transportLoaded = false;
				boolean mapLoaded = false;
				
				int progress = calculateProgressStatus();
				synchronized (resourceManger) {
					if (resourceManger.getBusyIndicator() != null) {
						resourceManger.getBusyIndicator().updateStatus(progress);
					}
				}
				while (!requests.isEmpty()) {
					Object req = requests.pop();
					if (req instanceof TileLoadDownloadRequest) {
						//此段为在联网条件下从服务器下载瓦片所调用的操作
						TileLoadDownloadRequest r = (TileLoadDownloadRequest) req;
						tileLoaded |= resourceManger.getRequestedImageTile(r) != null;
					} else if (req instanceof TransportLoadRequest) {
						if (!transportLoaded) {
							if (transportLoadRequest == null || asyncLoadingTransport == null) {
								startTransportLoadingThread();
								transportLoadRequest = (TransportLoadRequest) req;
								asyncLoadingTransport.post(transportLoadRequest.prepareToRun());
							} else if (transportLoadRequest.recalculateRequest((TransportLoadRequest) req)) {
								transportLoadRequest = (TransportLoadRequest) req;
								asyncLoadingTransport.post(transportLoadRequest.prepareToRun());
							}
							transportLoaded = true;
						}
					} else if (req instanceof MapLoadRequest) {
						if (!mapLoaded) {
							MapLoadRequest r = (MapLoadRequest) req;
							//如果req是MapLoadRequest实体，则进行loadmap操作
							//r.tileBox.containsLatLon(lat, lon);
							
							int LeftTopTilex = (int)r.tileBox.getLeftTopTile(r.tileBox.getZoom()).x;
							int LeftTopTiley = (int)r.tileBox.getLeftTopTile(r.tileBox.getZoom()).y;
							
							int RightBottomTilex = (int)r.tileBox.getRightBottomTile(r.tileBox.getZoom()).x;
							int RightBottomTiley = (int)r.tileBox.getRightBottomTile(r.tileBox.getZoom()).y;
							
//							log.warn("mytag:test：经度："+r.tileBox.getLatitude()+"  纬度："+r.tileBox.getLongitude());
//							log.warn("mytag:test：左上角经纬度："+r.tileBox.getLeftTopLatLon().getLongitude()+"  "+r.tileBox.getLeftTopLatLon().getLatitude());
//							log.warn("mytag:test：右下角经纬度："+r.tileBox.getRightBottomLatLon());
//							
//							log.warn("mytag:test：LeftTopTilex："+LeftTopTilex+"  LeftTopTiley："+LeftTopTiley);
//							log.warn("mytag:test：RightBottomTiley："+RightBottomTilex+"  RightBottomTiley："+RightBottomTiley);
//							
//							int testx =(int)MapUtils.getTileNumberX(r.tileBox.getZoom(), r.tileBox.getLongitude());
//							int testy =(int)MapUtils.getTileNumberY(r.tileBox.getZoom(), r.tileBox.getLatitude());
//							
//							log.warn("mytag:test：testx："+testx+"  testy："+testy);
							
						    
						    //将获取到的瓦片分布转换成经纬度范围并存储在相应的box中
							List<QuadRect>  BoxArray = new ArrayList<QuadRect>();
							int zoom = r.tileBox.getZoom();
							for(int tilex=LeftTopTilex;tilex<=RightBottomTilex;tilex++){
								for(int tiley=LeftTopTiley;tiley<=RightBottomTiley;tiley++){
									
									double top = tile2lat(tiley, zoom);
									double bottom = tile2lat(tiley + 1, zoom);
									double left = tile2lon(tilex, zoom);
									double right = tile2lon(tilex + 1, zoom);
									QuadRect tileBox = new QuadRect( left,  top,  right,  bottom);
									
									BoxArray.add(tileBox);
								}
								
							}
//							for(int i=0;i<BoxArray.size();i++){
//								log.warn("mytag:BoxArray"+(i+1)+":  "+BoxArray.get(i));
//							}//
							
							
//							for(int i = 0;i<BoxArray.size();i++){
//								resourceManger.getRenderer().loadMapforTile(r.tileBox,BoxArray.get(i), resourceManger.getMapTileDownloader().getDownloaderCallbacks());
//							}
							resourceManger.getRenderer().loadMap(r.tileBox, resourceManger.getMapTileDownloader().getDownloaderCallbacks());
							
							mapLoaded = true;
						}
					}
				}
				if (tileLoaded || amenityLoaded || transportLoaded || mapLoaded) {
					// use downloader callback
					for (IMapDownloaderCallback c : resourceManger.getMapTileDownloader().getDownloaderCallbacks()) {
						c.tileDownloaded(null);
					}
				}
				int newProgress = calculateProgressStatus();
				if (progress != newProgress) {
					synchronized (resourceManger) {
						if (resourceManger.getBusyIndicator() != null) {
							resourceManger.getBusyIndicator().updateStatus(newProgress);
						}
					}
				}
				sleep(750);
			} catch (InterruptedException e) {
				log.error(e, e);
			} catch (RuntimeException e) {
				log.error(e, e);
			}
		}
	}
	
	static double tile2lon(int x, int z) {
	     return x / Math.pow(2.0, z) * 360.0 - 180;
	  }
	 
	static double tile2lat(int y, int z) {
	    double n = Math.PI - (2.0 * Math.PI * y) / Math.pow(2.0, z);
	    return Math.toDegrees(Math.atan(Math.sinh(n)));
	  }

	public void requestToLoadImage(TileLoadDownloadRequest req) {
		requests.push(req);
	}

	public void requestToLoadMap(MapLoadRequest req) {
		requests.push(req);
	}

	public void requestToLoadTransport(TransportLoadRequest req) {
		requests.push(req);
	}
	
	public boolean isFileCurrentlyDownloaded(File fileToSave) {
		return resourceManger.getMapTileDownloader().isFileCurrentlyDownloaded(fileToSave);
	}

	public void requestToDownload(TileLoadDownloadRequest req) {
		resourceManger.getMapTileDownloader().requestToDownload(req);
	}

	protected static class TileLoadDownloadRequest extends DownloadRequest {

		public final String tileId;
		public final File dirWithTiles;
		public final ITileSource tileSource;

		public TileLoadDownloadRequest(File dirWithTiles, String url, File fileToSave, String tileId, ITileSource source, int tileX,
				int tileY, int zoom) {
			super(url, fileToSave, tileX, tileY, zoom);
			this.dirWithTiles = dirWithTiles;
			this.tileSource = source;
			this.tileId = tileId;
		}
	}

	protected class MapObjectLoadRequest<T> implements ResultMatcher<T> {
		protected double topLatitude;
		protected double bottomLatitude;
		protected double leftLongitude;
		protected double rightLongitude;
		protected boolean cancelled = false;
		protected volatile boolean running = false;

		public boolean isContains(double topLatitude, double leftLongitude, double bottomLatitude, double rightLongitude) {
			boolean inside = this.topLatitude >= topLatitude && this.leftLongitude <= leftLongitude
					&& this.rightLongitude >= rightLongitude && this.bottomLatitude <= bottomLatitude;
			return inside;
		}

		public void setBoundaries(double topLatitude, double leftLongitude, double bottomLatitude, double rightLongitude) {
			this.topLatitude = topLatitude;
			this.bottomLatitude = bottomLatitude;
			this.leftLongitude = leftLongitude;
			this.rightLongitude = rightLongitude;
		}
		
		public boolean isRunning() {
			return running && !cancelled;
		}
		
		public void start() {
			running = true;
		}
		
		public void finish() {
			running = false;
			// use downloader callback
			ArrayList<IMapDownloaderCallback> ls = new ArrayList<IMapDownloaderCallback>(resourceManger.getMapTileDownloader().getDownloaderCallbacks());
			for (IMapDownloaderCallback c : ls) {
				c.tileDownloaded(null);
			}
		}

		@Override
		public boolean isCancelled() {
			return cancelled;
		}

		@Override
		public boolean publish(T object) {
			return true;
		}

	}

	

	protected class TransportLoadRequest extends MapObjectLoadRequest<TransportStop> {
		private final List<TransportIndexRepository> repos;
		private int zoom;

		public TransportLoadRequest(List<TransportIndexRepository> repos, int zoom) {
			super();
			this.repos = repos;
			this.zoom = zoom;
		}

		public Runnable prepareToRun() {
			final double ntopLatitude = topLatitude + (topLatitude - bottomLatitude) / 2;
			final double nbottomLatitude = bottomLatitude - (topLatitude - bottomLatitude) / 2;
			final double nleftLongitude = leftLongitude - (rightLongitude - leftLongitude) / 2;
			final double nrightLongitude = rightLongitude + (rightLongitude - leftLongitude) / 2;
			setBoundaries(ntopLatitude, nleftLongitude, nbottomLatitude, nrightLongitude);
			return new Runnable() {
				@Override
				public void run() {
					start();
					try {
						for (TransportIndexRepository repository : repos) {
							repository.evaluateCachedTransportStops(ntopLatitude, nleftLongitude, nbottomLatitude, nrightLongitude, zoom,
									LIMIT_TRANSPORT, TransportLoadRequest.this);
						}
					} finally {
						finish();
					}
				}
			};
		}

		public boolean recalculateRequest(TransportLoadRequest req) {
			if (this.zoom != req.zoom) {
				return true;
			}
			return !isContains(req.topLatitude, req.leftLongitude, req.bottomLatitude, req.rightLongitude);
		}

	}

	protected static class MapLoadRequest {
		public final RotatedTileBox tileBox;

		public MapLoadRequest(RotatedTileBox tileBox) {
			super();
			this.tileBox = tileBox;
		}
	}


}