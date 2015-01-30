package net.osmand.plus.render;


import gnu.trove.iterator.TIntObjectIterator;
import gnu.trove.list.TLongList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.list.array.TLongArrayList;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.set.TLongSet;
import gnu.trove.set.hash.TLongHashSet;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.osmand.IProgress;
import net.osmand.ResultMatcher;
import net.osmand.NativeLibrary.NativeSearchResult;
import net.osmand.PlatformUtil;
import net.osmand.access.AccessibleToast;
import net.osmand.binary.BinaryMapDataObject;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteTypeRule;
import net.osmand.binary.RouteDataObject;
import net.osmand.binary.BinaryMapIndexReader.MapIndex;
import net.osmand.binary.BinaryMapIndexReader.SearchRequest;
import net.osmand.binary.BinaryMapIndexReader.TagValuePair;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteSubregion;
import net.osmand.data.QuadPointDouble;
import net.osmand.data.QuadRect;
import net.osmand.data.RotatedTileBox;
import net.osmand.map.MapTileDownloader.IMapDownloaderCallback;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandPlugin;
import net.osmand.plus.OsmandSettings;
import net.osmand.plus.OsmandSettings.CommonPreference;
import net.osmand.plus.R;
import net.osmand.plus.development.OsmandDevelopmentPlugin;
import net.osmand.plus.render.OsmandRenderer.MapDataObjectPrimitive;
import net.osmand.plus.render.OsmandRenderer.RenderingContext;
import net.osmand.plus.views.OsmandMapLayer.DrawSettings;
import net.osmand.render.RenderingRuleProperty;
import net.osmand.render.RenderingRuleSearchRequest;
import net.osmand.render.RenderingRuleStorageProperties;
import net.osmand.render.RenderingRulesStorage;
import net.osmand.util.Algorithms;
import net.osmand.util.MapAlgorithms;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

public class MapRenderRepositories {

	// It is needed to not draw object twice if user have map index that intersects by boundaries
	public static boolean checkForDuplicateObjectIds = true;
	
	private final static Log log = PlatformUtil.getLog(MapRenderRepositories.class);
	private final OsmandApplication context;
	private final static int zoomOnlyForBasemaps = 11;
	static int zoomForBaseRouteRendering  = 14;
	private Handler handler;
	private Map<String, BinaryMapIndexReader> files = new ConcurrentHashMap<String, BinaryMapIndexReader>();
	private Set<String> nativeFiles = new HashSet<String>();
	private OsmandRenderer renderer;
	
	private static int xtile = 0;
	private static int ytile = 0;
	
	


	// lat/lon box of requested vector data
	private QuadRect cObjectsBox = new QuadRect();
	// cached objects in order to render rotation without reloading data from db
	private List<BinaryMapDataObject> cObjects = new LinkedList<BinaryMapDataObject>();
	
	private List<BinaryMapDataObject> subObjects = new LinkedList<BinaryMapDataObject>();
	private NativeSearchResult cNativeObjects = null;

	// currently rendered box (not the same as already rendered)
	// this box is checked for interrupted process or
	private RotatedTileBox requestedBox = null;

	// location of rendered bitmap
	private RotatedTileBox prevBmpLocation = null;
	// already rendered bitmap
	private Bitmap prevBmp;
	// to track necessity of map download (1 (if basemap) + 2 (if normal map) 
	private int checkedRenderedState;
	private RotatedTileBox checkedBox;

	// location of rendered bitmap
	private RotatedTileBox bmpLocation = null;
	// already rendered bitmap
	private Bitmap bmp;
	// Field used in C++
	private boolean interrupted = false;
	private int renderedState = 0; 	// (1 (if basemap) + 2 (if normal map)
	private RenderingContext currentRenderingContext;
	private SearchRequest<BinaryMapDataObject> searchRequest;
	private OsmandSettings prefs;

	public MapRenderRepositories(OsmandApplication context) {
		this.context = context;
		this.renderer = new OsmandRenderer(context);
		handler = new Handler(Looper.getMainLooper());
		prefs = context.getSettings();
	}

	public Context getContext() {
		return context;
	}
	
	public OsmandRenderer getRenderer() {
		return renderer;
	}

	public void initializeNewResource(final IProgress progress, File file, BinaryMapIndexReader reader) {
		if (files.containsKey(file.getAbsolutePath())) {
			closeConnection(files.get(file.getAbsolutePath()), file.getAbsolutePath());
		
		}
		files.put(file.getAbsolutePath(), reader);
		NativeOsmandLibrary nativeLib = NativeOsmandLibrary.getLoadedLibrary();
		if (nativeLib != null) {
			if (!nativeLib.initMapFile(file.getAbsolutePath())) {
				log.error("Initializing native db " + file.getAbsolutePath() + " failed!"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			} else {
				nativeFiles.add(file.getAbsolutePath());
			}
		}
	}

	public RotatedTileBox getBitmapLocation() {
		return bmpLocation;
	}

	public RotatedTileBox getPrevBmpLocation() {
		return prevBmpLocation;
	}

	protected void closeConnection(BinaryMapIndexReader c, String file) {
		files.remove(file);
		if(nativeFiles.contains(file)){
			NativeOsmandLibrary lib = NativeOsmandLibrary.getLoadedLibrary();
			if(lib != null) {
				lib.closeMapFile(file);
				nativeFiles.remove(file);
			}
		}
		try {
			c.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public boolean containsLatLonMapData(double lat, double lon, int zoom) {
		int x = MapUtils.get31TileNumberX(lon);
		int y = MapUtils.get31TileNumberY(lat);
		for (BinaryMapIndexReader reader : files.values()) {
			if (reader.containsMapData(x, y, zoom)) {
				return true;
			}
		}
		return false;
	}

	public void clearAllResources() {
		clearCache();
		bmp = null;
		bmpLocation = null;
		for (String f : new ArrayList<String>(files.keySet())) {
			closeConnection(files.get(f), f);
		}
	}

	public boolean updateMapIsNeeded(RotatedTileBox box, DrawSettings drawSettings) {
		if (box == null) {
			return false;
		}
		if (requestedBox == null) {
			return true;
		}
		if (drawSettings.isUpdateVectorRendering()) {
			return true;
		}
		if (requestedBox.getZoom() != box.getZoom() ||
				requestedBox.getZoomScale() != box.getZoomScale()) {
			return true;
		}

		float deltaRotate = requestedBox.getRotate() - box.getRotate();
		if (deltaRotate > 180) {
			deltaRotate -= 360;
		} else if (deltaRotate < -180) {
			deltaRotate += 360;
		}
		if (Math.abs(deltaRotate) > 25) {
			return true;
		}
		return !requestedBox.containsTileBox(box);
	}

	public boolean isEmpty() {
		return files.isEmpty();
	}

	public void interruptLoadingMap() {
		interrupted = true;
		if (currentRenderingContext != null) {
			currentRenderingContext.interrupted = true;
		}
		if (searchRequest != null) {
			searchRequest.setInterrupted(true);
		}
	}

	private boolean checkWhetherInterrupted() {
		if (interrupted || (currentRenderingContext != null && currentRenderingContext.interrupted)) {
			requestedBox = bmpLocation;
			return true;
		}
		return false;
	}

	public boolean basemapExists() {
		for (BinaryMapIndexReader f : files.values()) {
			if (f.isBasemap()) {
				return true;
			}
		}
		return false;
	}
	
	
	private boolean loadVectorDataNative(QuadRect dataBox, final int zoom, final RenderingRuleSearchRequest renderingReq, 
			NativeOsmandLibrary library) {
		int leftX = MapUtils.get31TileNumberX(dataBox.left);
		int rightX = MapUtils.get31TileNumberX(dataBox.right);
		int bottomY = MapUtils.get31TileNumberY(dataBox.bottom);
		int topY = MapUtils.get31TileNumberY(dataBox.top);
		long now = System.currentTimeMillis();

		// check that everything is initialized
		for (String mapName : files.keySet()) {
			if (!nativeFiles.contains(mapName)) {
				nativeFiles.add(mapName);
				if (!library.initMapFile(mapName)) {
					continue;
				}
				log.debug("Native resource " + mapName + " initialized"); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		
		NativeSearchResult resultHandler = library.searchObjectsForRendering(leftX, rightX, topY, bottomY, zoom, renderingReq,
				checkForDuplicateObjectIds, this, "");
		if (checkWhetherInterrupted()) {
			resultHandler.deleteNativeResult();
			return false;
		}
		if(cNativeObjects != null) {
			cNativeObjects.deleteNativeResult();
		}
		cNativeObjects = resultHandler;
		cObjectsBox = dataBox;
		log.info(String.format("BLat=%s, TLat=%s, LLong=%s, RLong=%s, zoom=%s", //$NON-NLS-1$
				dataBox.bottom, dataBox.top, dataBox.left, dataBox.right, zoom));
		log.info(String.format("Native search: %s ms ", System.currentTimeMillis() - now)); //$NON-NLS-1$
		return true;
	}
	
	private void readRouteDataAsMapObjects(SearchRequest<BinaryMapDataObject> sr, BinaryMapIndexReader c, 
			final ArrayList<BinaryMapDataObject> tempResult, final TLongSet ids) {
		final boolean basemap = c.isBasemap();
		try {
			for (RouteRegion reg : c.getRoutingIndexes()) {
				List<RouteSubregion> parent = sr.getZoom() < 15 ? reg.getBaseSubregions() : reg.getSubregions();
				List<RouteSubregion> searchRouteIndexTree = c.searchRouteIndexTree(sr, parent);
				final MapIndex nmi = new MapIndex();
				c.loadRouteIndexData(searchRouteIndexTree, new ResultMatcher<RouteDataObject>() {

					@Override
					public boolean publish(RouteDataObject r) {
						if (basemap) {
							renderedState |= 1;
						} else {
							renderedState |= 2;
						}
						if (checkForDuplicateObjectIds && !basemap) {
							if (ids.contains(r.getId()) && r.getId() > 0) {
								// do not add object twice
								return false;
							}
							ids.add(r.getId());
						}
						int[] coordinantes = new int[r.getPointsLength() * 2];
						int[] roTypes = r.getTypes();
						for(int k = 0; k < roTypes.length; k++) {
							int type = roTypes[k];
							registerMissingType(nmi, r, type);
						}
						for(int k = 0; k < coordinantes.length/2; k++ ) {
							coordinantes[2 * k] = r.getPoint31XTile(k);
							coordinantes[2 * k + 1] = r.getPoint31YTile(k);
						}
						BinaryMapDataObject mo = new BinaryMapDataObject(coordinantes, roTypes, new int[0][], r.getId());
						TIntObjectHashMap<String> names = r.getNames();
						if(names != null) {
							TIntObjectIterator<String> it = names.iterator();
							while(it.hasNext()) {
								it.advance();
								registerMissingType(nmi, r, it.key());
								mo.putObjectName(it.key(), it.value());
							}
						}
						mo.setMapIndex(nmi);
						tempResult.add(mo);
						return false;
					}

					private void registerMissingType(final MapIndex nmi, RouteDataObject r, int type) {
						if (!nmi.isRegisteredRule(type)) {
							RouteTypeRule rr = r.region.quickGetEncodingRule(type);
							String tag = rr.getTag();
							int additional = ("highway".equals(tag) || "route".equals(tag) || "railway".equals(tag)
									|| "aeroway".equals(tag) || "aerialway".equals(tag)) ? 0 : 1;
							nmi.initMapEncodingRule(additional, type, rr.getTag(), rr.getValue());
						}
					}

					@Override
					public boolean isCancelled() {
						return !interrupted;
					}
				});
			}
		} catch (IOException e) {
			log.debug("Search failed " + c.getRegionNames(), e); //$NON-NLS-1$
		}
	}

	public boolean loadVectorDataforTile(QuadRect dataBox,ArrayList<BinaryMapDataObject> tempResult, final int zoom, final RenderingRuleSearchRequest renderingReq) {

		double cBottomLatitude = dataBox.bottom;
		double cTopLatitude = dataBox.top;
		double cLeftLongitude = dataBox.left;
		double cRightLongitude = dataBox.right;

		long now = System.currentTimeMillis();

		System.gc(); // to clear previous objects
//		ArrayList<BinaryMapDataObject> tempResult = new ArrayList<BinaryMapDataObject>();
		ArrayList<BinaryMapDataObject> basemapResult = new ArrayList<BinaryMapDataObject>();
		
		int[] count = new int[]{0};
		boolean[] ocean = new boolean[]{false};
		boolean[] land = new boolean[]{false};
		List<BinaryMapDataObject> coastLines = new ArrayList<BinaryMapDataObject>();
		List<BinaryMapDataObject> basemapCoastLines = new ArrayList<BinaryMapDataObject>();
		int leftX = MapUtils.get31TileNumberX(cLeftLongitude);
		int rightX = MapUtils.get31TileNumberX(cRightLongitude);
		int bottomY = MapUtils.get31TileNumberY(cBottomLatitude);
		int topY = MapUtils.get31TileNumberY(cTopLatitude);
		
		SearchRequest<BinaryMapDataObject> sr = BinaryMapIndexReader.buildSearchRequest(leftX, rightX, topY, bottomY, zoom, null);
		TLongSet ids = new TLongHashSet();
		MapIndex mi = readMapObjectsForRenderingforTile(zoom, renderingReq, tempResult, basemapResult, ids, count, ocean,
				land, coastLines, basemapCoastLines, leftX, rightX, bottomY, topY);
		
		int renderRouteDataFile = 0;
		if (renderingReq.searchRenderingAttribute("showRoadMapsAttribute")) {
			renderRouteDataFile = renderingReq.getIntPropertyValue(renderingReq.ALL.R_ATTR_INT_VALUE);
		}
		
//		if (checkWhetherInterrupted()) {
//			return false;
//		}
		
		boolean objectsFromMapSectionRead = tempResult.size() > 0;
		if (renderRouteDataFile >= 0 && zoom >= zoomOnlyForBasemaps ) {
			
			for (BinaryMapIndexReader c : files.values()) {
				// false positive case when we have 2 sep maps Country-roads & Country
				if(c.getMapIndexes().size() == 0 || renderRouteDataFile == 1) {
					readRouteDataAsMapObjects(sr, c, tempResult, ids);
				}
			}
			log.info(String.format("Route objects %s", tempResult.size() +""));
		}

		String coastlineTime = "";
		boolean addBasemapCoastlines = true;
		boolean emptyData = zoom > zoomOnlyForBasemaps && tempResult.isEmpty() && coastLines.isEmpty();
		boolean basemapMissing = zoom <= zoomOnlyForBasemaps && basemapCoastLines.isEmpty() && mi == null;
		boolean detailedLandData = zoom >= zoomForBaseRouteRendering && tempResult.size() > 0  && objectsFromMapSectionRead;
		
		
		if (!coastLines.isEmpty()) {
			long ms = System.currentTimeMillis();
			boolean coastlinesWereAdded = processCoastlines(coastLines, leftX, rightX, bottomY, topY, zoom,
					basemapCoastLines.isEmpty(), true, tempResult);
			addBasemapCoastlines = (!coastlinesWereAdded && !detailedLandData) || zoom <= zoomOnlyForBasemaps;
			coastlineTime = "(coastline " + (System.currentTimeMillis() - ms) + " ms )";
		} else {
			addBasemapCoastlines = !detailedLandData;
		}
		
		
		if (addBasemapCoastlines) {
			long ms = System.currentTimeMillis();
			boolean coastlinesWereAdded = processCoastlines(basemapCoastLines, leftX, rightX, bottomY, topY, zoom,
					true, true, tempResult);
			addBasemapCoastlines = !coastlinesWereAdded;
			coastlineTime = "(coastline " + (System.currentTimeMillis() - ms) + " ms )";
		}
		
		
		if (addBasemapCoastlines && mi != null) {
			BinaryMapDataObject o = new BinaryMapDataObject(new int[]{leftX, topY, rightX, topY, rightX, bottomY, leftX, bottomY, leftX,
					topY}, new int[]{ocean[0] && !land[0] ? mi.coastlineEncodingType : (mi.landEncodingType)}, null, -1);
			o.setMapIndex(mi);
			tempResult.add(o);
		}
		
		
		if (emptyData || basemapMissing) {
			// message
			MapIndex mapIndex;
			if (!tempResult.isEmpty()) {
				mapIndex = tempResult.get(0).getMapIndex();
			} else {
				mapIndex = new MapIndex();
				mapIndex.initMapEncodingRule(0, 1, "natural", "coastline");
				mapIndex.initMapEncodingRule(0, 2, "name", "");
			}
		}
		if (zoom <= zoomOnlyForBasemaps || emptyData) {
			tempResult.addAll(basemapResult);
		}


		if (count[0] > 0) {
			log.info(String.format("BLat=%s, TLat=%s, LLong=%s, RLong=%s, zoom=%s", //$NON-NLS-1$
					cBottomLatitude, cTopLatitude, cLeftLongitude, cRightLongitude, zoom));
			log.info(String.format("Searching: %s ms  %s (%s results found)", System.currentTimeMillis() - now, coastlineTime, count[0])); //$NON-NLS-1$
		}


		return true;
	
		
	}
	public boolean loadVectorData(QuadRect dataBox, final int zoom, final RenderingRuleSearchRequest renderingReq) {
		double cBottomLatitude = dataBox.bottom;
		double cTopLatitude = dataBox.top;
		double cLeftLongitude = dataBox.left;
		double cRightLongitude = dataBox.right;

		long now = System.currentTimeMillis();

		System.gc(); // to clear previous objects
		ArrayList<BinaryMapDataObject> tempResult = new ArrayList<BinaryMapDataObject>();
		ArrayList<BinaryMapDataObject> basemapResult = new ArrayList<BinaryMapDataObject>();
		
		int[] count = new int[]{0};
		boolean[] ocean = new boolean[]{false};
		boolean[] land = new boolean[]{false};
		List<BinaryMapDataObject> coastLines = new ArrayList<BinaryMapDataObject>();
		List<BinaryMapDataObject> basemapCoastLines = new ArrayList<BinaryMapDataObject>();
		int leftX = MapUtils.get31TileNumberX(cLeftLongitude);
		int rightX = MapUtils.get31TileNumberX(cRightLongitude);
		int bottomY = MapUtils.get31TileNumberY(cBottomLatitude);
		int topY = MapUtils.get31TileNumberY(cTopLatitude);
		
		log.warn("mytag:leftX"+leftX+"rightX"+rightX+"bottomY"+bottomY+"topY"+topY);
		
		TLongSet ids = new TLongHashSet();
		MapIndex mi = readMapObjectsForRendering(zoom, renderingReq, tempResult, basemapResult, ids, count, ocean,
				land, coastLines, basemapCoastLines, leftX, rightX, bottomY, topY);//从文件中读取对象
		
		log.warn("mytag:0!!!!");
		int renderRouteDataFile = 0;
		if (renderingReq.searchRenderingAttribute("showRoadMapsAttribute")) {
			renderRouteDataFile = renderingReq.getIntPropertyValue(renderingReq.ALL.R_ATTR_INT_VALUE);
		}
		log.warn("mytag:000!!!!");
		if (checkWhetherInterrupted()) {
			return false;
		}
		log.warn("mytag:1!!!!");
		
		boolean objectsFromMapSectionRead = tempResult.size() > 0;
		if (renderRouteDataFile >= 0 && zoom >= zoomOnlyForBasemaps ) {
			searchRequest = BinaryMapIndexReader.buildSearchRequest(leftX, rightX, topY, bottomY, zoom, null);
			for (BinaryMapIndexReader c : files.values()) {
				// false positive case when we have 2 sep maps Country-roads & Country
				if(c.getMapIndexes().size() == 0 || renderRouteDataFile == 1) {
					readRouteDataAsMapObjects(searchRequest, c, tempResult, ids);//导航时用作增加矢量在地图上显示路线
				}
			}
			log.info(String.format("Route objects %s", tempResult.size() +""));
		}
		log.warn("mytag:2!!!!");

		String coastlineTime = "";
		boolean addBasemapCoastlines = true;
		boolean emptyData = zoom > zoomOnlyForBasemaps && tempResult.isEmpty() && coastLines.isEmpty();
		boolean basemapMissing = zoom <= zoomOnlyForBasemaps && basemapCoastLines.isEmpty() && mi == null;
		boolean detailedLandData = zoom >= zoomForBaseRouteRendering && tempResult.size() > 0  && objectsFromMapSectionRead;
		
		log.warn("mytag:3!!!!");
		
		if (!coastLines.isEmpty()) {
			long ms = System.currentTimeMillis();
			boolean coastlinesWereAdded = processCoastlines(coastLines, leftX, rightX, bottomY, topY, zoom,
					basemapCoastLines.isEmpty(), true, tempResult);
			addBasemapCoastlines = (!coastlinesWereAdded && !detailedLandData) || zoom <= zoomOnlyForBasemaps;
			coastlineTime = "(coastline " + (System.currentTimeMillis() - ms) + " ms )";
		} else {
			addBasemapCoastlines = !detailedLandData;
		}
		
		log.warn("mytag:4!!!!");
		
		if (addBasemapCoastlines) {
			long ms = System.currentTimeMillis();
			boolean coastlinesWereAdded = processCoastlines(basemapCoastLines, leftX, rightX, bottomY, topY, zoom,
					true, true, tempResult);
			addBasemapCoastlines = !coastlinesWereAdded;
			coastlineTime = "(coastline " + (System.currentTimeMillis() - ms) + " ms )";
		}
		
		log.warn("mytag:5!!!!");
		
		if (addBasemapCoastlines && mi != null) {
			BinaryMapDataObject o = new BinaryMapDataObject(new int[]{leftX, topY, rightX, topY, rightX, bottomY, leftX, bottomY, leftX,
					topY}, new int[]{ocean[0] && !land[0] ? mi.coastlineEncodingType : (mi.landEncodingType)}, null, -1);
			o.setMapIndex(mi);
			tempResult.add(o);
		}
		
		log.warn("mytag:6!!!!");
		
		if (emptyData || basemapMissing) {
			// message
			MapIndex mapIndex;
			if (!tempResult.isEmpty()) {
				mapIndex = tempResult.get(0).getMapIndex();
			} else {
				mapIndex = new MapIndex();
				mapIndex.initMapEncodingRule(0, 1, "natural", "coastline");
				mapIndex.initMapEncodingRule(0, 2, "name", "");
			}
		}
		if (zoom <= zoomOnlyForBasemaps || emptyData) {
			tempResult.addAll(basemapResult);
		}


		if (count[0] > 0) {
			log.info(String.format("BLat=%s, TLat=%s, LLong=%s, RLong=%s, zoom=%s", //$NON-NLS-1$
					cBottomLatitude, cTopLatitude, cLeftLongitude, cRightLongitude, zoom));
			log.info(String.format("Searching: %s ms  %s (%s results found)", System.currentTimeMillis() - now, coastlineTime, count[0])); //$NON-NLS-1$
		}
		log.warn("mytag:7!!!!");


		cObjects = tempResult;
		cObjectsBox = dataBox;
		log.warn("mytag:8!!!!");
		

		return true;
	}

	private MapIndex readMapObjectsForRenderingforTile(final int zoom, final RenderingRuleSearchRequest renderingReq,
			ArrayList<BinaryMapDataObject> tempResult, ArrayList<BinaryMapDataObject> basemapResult, 
			TLongSet ids, int[] count, boolean[] ocean, boolean[] land, List<BinaryMapDataObject> coastLines,
			List<BinaryMapDataObject> basemapCoastLines, int leftX, int rightX, int bottomY, int topY) {

		BinaryMapIndexReader.SearchFilter searchFilter = new BinaryMapIndexReader.SearchFilter() {
			@Override
			public boolean accept(TIntArrayList types, BinaryMapIndexReader.MapIndex root) {
				for (int j = 0; j < types.size(); j++) {
					int type = types.get(j);
					TagValuePair pair = root.decodeType(type);
					if (pair != null) {
						// TODO is it fast enough ?
						for (int i = 1; i <= 3; i++) {
							renderingReq.setIntFilter(renderingReq.ALL.R_MINZOOM, zoom);
							renderingReq.setStringFilter(renderingReq.ALL.R_TAG, pair.tag);
							renderingReq.setStringFilter(renderingReq.ALL.R_VALUE, pair.value);
							if (renderingReq.search(i, false)) {
								return true;
							}
						}
						renderingReq.setStringFilter(renderingReq.ALL.R_TAG, pair.tag);
						renderingReq.setStringFilter(renderingReq.ALL.R_VALUE, pair.value);
						if (renderingReq.search(RenderingRulesStorage.TEXT_RULES, false)) {
							return true;
						}
					}
				}
				return false;
			}

		};
		if (zoom > 16) {
			searchFilter = null;
		}
		MapIndex mi = null;
		SearchRequest<BinaryMapDataObject> sr = BinaryMapIndexReader.buildSearchRequest(leftX, rightX, topY, bottomY, zoom, searchFilter);
		for (BinaryMapIndexReader c : files.values()) {
			boolean basemap = c.isBasemap();
			sr.clearSearchResults();
			List<BinaryMapDataObject> res;
			try {
				res = c.searchMapIndex(sr);
			} catch (IOException e) {
				res = new ArrayList<BinaryMapDataObject>();
				log.debug("Search failed " + c.getRegionNames(), e); //$NON-NLS-1$
			}
			if(res.size() > 0) {
				if(basemap) {
					renderedState |= 1;
				} else {
					renderedState |= 2;
				}
			}
			for (BinaryMapDataObject r : res) {
				if (checkForDuplicateObjectIds && !basemap) {
					if (ids.contains(r.getId()) && r.getId() > 0) {
						// do not add object twice
						continue;
					}
					ids.add(r.getId());
				}
				count[0]++;

				if (r.containsType(r.getMapIndex().coastlineEncodingType)) {
					if (basemap) {
						basemapCoastLines.add(r);
					} else {
						coastLines.add(r);
					}
				} else {
					// do not mess coastline and other types
					if (basemap) {
						basemapResult.add(r);
					} else {
						tempResult.add(r);
					}
				}
//				if (checkWhetherInterrupted()) {
//					return null;
//				}
			}

			if (sr.isOcean()) {
				mi = c.getMapIndexes().get(0);
				ocean[0] = true;
			}
			if (sr.isLand()) {
				mi = c.getMapIndexes().get(0);
				land[0] = true;
			}
		}
		return mi;
	
		
	}

	private MapIndex readMapObjectsForRendering(final int zoom, final RenderingRuleSearchRequest renderingReq,
			ArrayList<BinaryMapDataObject> tempResult, ArrayList<BinaryMapDataObject> basemapResult, 
			TLongSet ids, int[] count, boolean[] ocean, boolean[] land, List<BinaryMapDataObject> coastLines,
			List<BinaryMapDataObject> basemapCoastLines, int leftX, int rightX, int bottomY, int topY) {
		BinaryMapIndexReader.SearchFilter searchFilter = new BinaryMapIndexReader.SearchFilter() {
			@Override
			public boolean accept(TIntArrayList types, BinaryMapIndexReader.MapIndex root) {
				for (int j = 0; j < types.size(); j++) {
					int type = types.get(j);
					TagValuePair pair = root.decodeType(type);
					if (pair != null) {
						// TODO is it fast enough ?
						for (int i = 1; i <= 3; i++) {
							renderingReq.setIntFilter(renderingReq.ALL.R_MINZOOM, zoom);
							renderingReq.setStringFilter(renderingReq.ALL.R_TAG, pair.tag);
							renderingReq.setStringFilter(renderingReq.ALL.R_VALUE, pair.value);
							if (renderingReq.search(i, false)) {
								return true;
							}
						}
						renderingReq.setStringFilter(renderingReq.ALL.R_TAG, pair.tag);
						renderingReq.setStringFilter(renderingReq.ALL.R_VALUE, pair.value);
						if (renderingReq.search(RenderingRulesStorage.TEXT_RULES, false)) {
							return true;
						}
					}
				}
				return false;
			}

		};
		if (zoom > 16) {
			searchFilter = null;
		}
		MapIndex mi = null;
		searchRequest = BinaryMapIndexReader.buildSearchRequest(leftX, rightX, topY, bottomY, zoom, searchFilter);
		for (BinaryMapIndexReader c : files.values()) {
			boolean basemap = c.isBasemap();
			searchRequest.clearSearchResults();
			List<BinaryMapDataObject> res;
			try {
				res = c.searchMapIndex(searchRequest);
			} catch (IOException e) {
				res = new ArrayList<BinaryMapDataObject>();
				log.debug("Search failed " + c.getRegionNames(), e); //$NON-NLS-1$
			}
			if(res.size() > 0) {
				if(basemap) {
					renderedState |= 1;
				} else {
					renderedState |= 2;
				}
			}
			for (BinaryMapDataObject r : res) {
				if (checkForDuplicateObjectIds && !basemap) {
					if (ids.contains(r.getId()) && r.getId() > 0) {
						// do not add object twice
						continue;
					}
					ids.add(r.getId());
				}
				count[0]++;

				if (r.containsType(r.getMapIndex().coastlineEncodingType)) {
					if (basemap) {
						basemapCoastLines.add(r);
					} else {
						coastLines.add(r);
					}
				} else {
					// do not mess coastline and other types
					if (basemap) {
						basemapResult.add(r);
					} else {
						tempResult.add(r);
					}
				}
				if (checkWhetherInterrupted()) {
					return null;
				}
			}

			if (searchRequest.isOcean()) {
				mi = c.getMapIndexes().get(0);
				ocean[0] = true;
			}
			if (searchRequest.isLand()) {
				mi = c.getMapIndexes().get(0);
				land[0] = true;
			}
		}
		return mi;
	}

	private void validateLatLonBox(QuadRect box) {
		if (box.top > 90) {
			box.top = 85.5f;
		}
		if (box.bottom < -90) {
			box.bottom = -85.5f;
		}
		if (box.left <= -180) {
			box.left = -179.5f;
		}
		if (box.right > 180) {
			box.right = 180.0f;
		}
	}

	public RotatedTileBox getCheckedBox() {
		return checkedBox;
	}
	
	public int getCheckedRenderedState() {
		// to track necessity of map download (1 (if basemap) + 2 (if normal map)
		return checkedRenderedState;
	}

	
	public static String getTileNumber(final double lat, final double lon, final int zoom) {
		    xtile = (int)Math.floor( (lon + 180) / 360 * (1<<zoom) ) ;
		    ytile = (int)Math.floor( (1 - Math.log(Math.tan(Math.toRadians(lat)) + 1 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2 * (1<<zoom) ) ;
		    if (xtile < 0)
		     xtile=0;
		    if (xtile >= (1<<zoom))
		     xtile=((1<<zoom)-1);
		    if (ytile < 0)
		     ytile=0;
		    if (ytile >= (1<<zoom))
		     ytile=((1<<zoom)-1);
		    return("" + zoom + "/" + xtile + "/" + ytile);
		   }
	
	static double tile2lon(int x, int z) {
	     return x / Math.pow(2.0, z) * 360.0 - 180;
	  }
	 
	static double tile2lat(int y, int z) {
	    double n = Math.PI - (2.0 * Math.PI * y) / Math.pow(2.0, z);
	    return Math.toDegrees(Math.atan(Math.sinh(n)));
	  }
	
	public synchronized void loadMap(RotatedTileBox tileRect, List<IMapDownloaderCallback> notifyList) {
		interrupted = false;
		//test
		if(files.isEmpty()){
			log.warn("mytag:test：kong");
			
		}
		int LeftTopTilex = (int)tileRect.getLeftTopTile(tileRect.getZoom()).x;
		int LeftTopTiley = (int)tileRect.getLeftTopTile(tileRect.getZoom()).y;
		
		int RightBottomTilex = (int)tileRect.getRightBottomTile(tileRect.getZoom()).x;
		int RightBottomTiley = (int)tileRect.getRightBottomTile(tileRect.getZoom()).y;
		
		log.warn("mytag:test：经度："+tileRect.getLatitude()+"  纬度："+tileRect.getLongitude());
		log.warn("mytag:test：左上角经纬度："+tileRect.getLeftTopLatLon().getLongitude()+"  "+tileRect.getLeftTopLatLon().getLatitude());
		log.warn("mytag:test：右下角经纬度："+tileRect.getRightBottomLatLon());
		
		log.warn("mytag:test：LeftTopTilex："+LeftTopTilex+"  LeftTopTiley："+LeftTopTiley);
		log.warn("mytag:test：RightBottomTiley："+RightBottomTilex+"  RightBottomTiley："+RightBottomTiley);
		
		int testx =(int)MapUtils.getTileNumberX(tileRect.getZoom(), tileRect.getLongitude());
		int testy =(int)MapUtils.getTileNumberY(tileRect.getZoom(), tileRect.getLatitude());
		
		log.warn("mytag:test：testx："+testx+"  testy："+testy);
		
		getTileNumber(tileRect.getLatitude(),tileRect.getLongitude(),tileRect.getZoom());
		log.warn("mytag:中心瓦片所对应的x、y、z分别是："+xtile+"  "+ytile+"   "+tileRect.getZoom());
		log.warn("mytag:test："+MapUtils.getTileNumberX(tileRect.getZoom(), tileRect.getLongitude())+"  "+MapUtils.getTileNumberY(tileRect.getZoom(), tileRect.getLatitude())+"   "+tileRect.getZoom());
		
	    
	    //将获取到的瓦片分布转换成经纬度范围并存储在相应的box中
		List<QuadRect>  BoxArray = new ArrayList<QuadRect>();
		int zoom = tileRect.getZoom();
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
		for(int i=0;i<BoxArray.size();i++){
			log.warn("mytag:BoxArray"+(i+1)+":  "+BoxArray.get(i));
		}//
		
		if (currentRenderingContext != null) {
			currentRenderingContext = null;
		}
		try {
			log.warn("counter here!!!");
			// find selected rendering type
			OsmandApplication app = ((OsmandApplication) context.getApplicationContext());
			boolean nightMode = app.getDaynightHelper().isNightMode();
			// boolean moreDetail = prefs.SHOW_MORE_MAP_DETAIL.get();
			RenderingRulesStorage storage = app.getRendererRegistry().getCurrentSelectedRenderer();
			RenderingRuleSearchRequest renderingReq = new RenderingRuleSearchRequest(storage);
			renderingReq.setBooleanFilter(renderingReq.ALL.R_NIGHT_MODE, nightMode);
			for (RenderingRuleProperty customProp : storage.PROPS.getCustomRules()) {
				if (customProp.isBoolean()) {
					CommonPreference<Boolean> pref = prefs.getCustomRenderBooleanProperty(customProp.getAttrName());
					renderingReq.setBooleanFilter(customProp, pref.get());
				} else {
					CommonPreference<String> settings = prefs.getCustomRenderProperty(customProp.getAttrName());
					String res = settings.get();
					if (!Algorithms.isEmpty(res)) {
						if (customProp.isString()) {
							renderingReq.setStringFilter(customProp, res);
						} else if (customProp.isBoolean()) {
							renderingReq.setBooleanFilter(customProp, "true".equalsIgnoreCase(res));
						} else {
							try {
								renderingReq.setIntFilter(customProp, Integer.parseInt(res));
							} catch (NumberFormatException e) {
								e.printStackTrace();
							}
						}
					}
				}
			}
			renderingReq.saveState();
			NativeOsmandLibrary nativeLib = !prefs.SAFE_MODE.get() ? NativeOsmandLibrary.getLibrary(storage, context) : null;

			// prevent editing
			//tileRect.setLatLonCenter(12.12457, 24.4865);
			//QuadRect dataBox = new QuadRect( left,  top,  right,  bottom);
			//tileRect.setLatLon(dataBox);
			RotatedTileBox temp ;
			temp = tileRect.copy();
			
			//temp.setLatLon(dataBox);
			//RotatedTileBox latlon = new RotatedTileBox(temp);
			//tileRect.setLatLonCenter(lat, lon)
			//temp.setLatLonCenter((top+bottom)/2, (left+right)/2);//如何获取瓦片中心经纬度
			
//			double lat = temp.getLatFromPixel(temp.getCenterPixelPoint().x, temp.getCenterPixelPoint().y);
//			double lon = temp.getLonFromPixel(temp.getCenterPixelPoint().x, temp.getCenterPixelPoint().y);
			
			//log.warn("mytag:123："+temp.getLeftTopLatLon()+"   "+tileRect.getLeftTopLatLon());
//			temp.setLatLonCenter(latlon.getLatitude(), latlon.getLongitude());
//			log.warn("mytag:latlon.getLatitude()："+latlon.getLatitude());
//			log.warn("mytag:latlon.getLongitude()："+latlon.getLongitude());
//			requestedBox.setLatLon(dataBox);
			
			
			
//			boolean tileloaded;
//			ArrayList<BinaryMapDataObject> tempResult = new ArrayList<BinaryMapDataObject>();
//			for(int i=0;i<BoxArray.size();i++){
//				
//				tileloaded = loadVectorDataforTile(BoxArray.get(i),tempResult, tileRect.getZoom(), renderingReq);
//				
//				if(tileloaded){
//					
//					for(int j=0;j<tempResult.size();j++){
//						subObjects.add(tempResult.get(j));
//					}
//					tempResult.clear();
//					log.warn("mytag:BoxArray"+(i+1)+":成功转置  ");
//				}else{
//					log.warn("mytag:BoxArray"+(i+1)+":未成功转置  ");
//				}
//				
//			}
//			log.warn("mytag:subObjects"+":  "+subObjects.size());
////			
			
			
			
			
			requestedBox = new RotatedTileBox(tileRect);
			requestedBox.getLeftTopLatLon();
			log.warn("mytag:requestedBox.getLeftTopLatLon()："+requestedBox.getLeftTopLatLon());
			
			//requestedBox.getTileBounds();
			
			//requestedBox.setLatLon(testBox);
			
			double top = tile2lat(ytile, zoom);
			double bottom = tile2lat(ytile + 1, zoom);
			double left = tile2lon(xtile, zoom);
			double right = tile2lon(xtile + 1, zoom);
			QuadRect tileBox = new QuadRect( left,  top,  right,  bottom);
			
			// calculate data box
			//QuadRect dataBox = requestedBox.getLatLonBounds(); //
			QuadRect dataBox = new QuadRect( left,  top,  right,  bottom); //
			
			//temp.setLatLon(dataBox);
			
			
			//log.warn("counter here!!!"+dataBox.left);
			 
			long now = System.currentTimeMillis();
			//
			if (cObjectsBox.left > dataBox.left || cObjectsBox.top > dataBox.top || cObjectsBox.right < dataBox.right
					|| cObjectsBox.bottom < dataBox.bottom || (nativeLib != null) == (cNativeObjects == null)) {
				// increase data box in order for rotate
				if ((dataBox.right - dataBox.left) > (dataBox.top - dataBox.bottom)) {
					double wi = (dataBox.right - dataBox.left) * .2;
//					dataBox.left -= wi;
//					dataBox.right += wi;
				} else {
					double hi = (dataBox.top - dataBox.bottom) * .2;
//					dataBox.top += hi;
//					dataBox.bottom -= hi;
				}
//				validateLatLonBox(dataBox);
				renderedState = 0;
				boolean loaded;
				if(nativeLib != null) {
					cObjects = new LinkedList<BinaryMapDataObject>();
					loaded = loadVectorDataNative(dataBox, requestedBox.getZoom(), renderingReq, nativeLib);
				} else {
					cNativeObjects = null;
					loaded = loadVectorData(dataBox, requestedBox.getZoom(), renderingReq);
					//从本地矢量文件中读取相应信息
					
				}
				if (!loaded || checkWhetherInterrupted()) {
					return;
				}
			}
			
			//
			
			
			
			//loadVectorData(testBox,requestedBox.getZoom(),renderingReq);
			final long searchTime = System.currentTimeMillis() - now;

			currentRenderingContext = new OsmandRenderer.RenderingContext(context);
			renderingReq.clearState();
			renderingReq.setIntFilter(renderingReq.ALL.R_MINZOOM, requestedBox.getZoom());
			if(renderingReq.searchRenderingAttribute(RenderingRuleStorageProperties.A_DEFAULT_COLOR)) {
				currentRenderingContext.defaultColor = renderingReq.getIntPropertyValue(renderingReq.ALL.R_ATTR_COLOR_VALUE);
			}
			renderingReq.clearState();
			renderingReq.setIntFilter(renderingReq.ALL.R_MINZOOM, requestedBox.getZoom());
			if(renderingReq.searchRenderingAttribute(RenderingRuleStorageProperties.A_SHADOW_RENDERING)) {
				currentRenderingContext.shadowRenderingMode = renderingReq.getIntPropertyValue(renderingReq.ALL.R_ATTR_INT_VALUE);
				currentRenderingContext.shadowRenderingColor = renderingReq.getIntPropertyValue(renderingReq.ALL.R_SHADOW_COLOR);
			}
			if(renderingReq.searchRenderingAttribute("polygonMinSizeToDisplay")) {
				currentRenderingContext.polygonMinSizeToDisplay = renderingReq.getIntPropertyValue(renderingReq.ALL.R_ATTR_INT_VALUE);
			}
			final QuadPointDouble lt = requestedBox.getLeftTopTile(requestedBox.getZoom());
//			LatLon ltn = requestedBox.getLeftTopLatLon();
			final float mapDensity = (float) Math.pow(2, requestedBox.getZoomScale());
			final double tileDivisor = MapUtils.getPowZoom(31 - requestedBox.getZoom() -
						requestedBox.getZoomScale());
			currentRenderingContext.leftX = lt.x * MapUtils.getPowZoom(requestedBox.getZoomScale());
					// MapUtils.get31TileNumberX(ltn.getLongitude()) / tileDivisor;
			currentRenderingContext.topY = lt.y * MapUtils.getPowZoom(requestedBox.getZoomScale());
					//MapUtils.get31TileNumberY(ltn.getLatitude()) / tileDivisor;
			currentRenderingContext.zoom = requestedBox.getZoom();
			currentRenderingContext.rotate = requestedBox.getRotate();
			currentRenderingContext.width = requestedBox.getPixWidth();
			currentRenderingContext.height = requestedBox.getPixHeight();
			
//			currentRenderingContext.width = 256;
//			currentRenderingContext.height = 256;
			currentRenderingContext.nightMode = nightMode;
			currentRenderingContext.preferredLocale = prefs.MAP_PREFERRED_LOCALE.get();
			currentRenderingContext.setDensityValue(mapDensity);
			currentRenderingContext.screenDensityRatio = mapDensity / Math.max(1, requestedBox.getDensity()) ;
			// init rendering context
			currentRenderingContext.tileDivisor = tileDivisor;
			if (checkWhetherInterrupted()) {
				return;
			}

			now = System.currentTimeMillis();
			Bitmap bmp;
			boolean transparent = false;
			RenderingRuleProperty rr = storage.PROPS.get("noPolygons");
			if (rr != null) {
				transparent = renderingReq.getIntPropertyValue(rr) > 0;
			}

			// 1. generate image step by step
			Bitmap reuse = prevBmp;
			this.prevBmp = this.bmp;
			this.prevBmpLocation = this.bmpLocation;
			if (reuse != null && reuse.getWidth() == currentRenderingContext.width && reuse.getHeight() == currentRenderingContext.height) {
				bmp = reuse;
				bmp.eraseColor(currentRenderingContext.defaultColor);
			} else {
				if(reuse != null){
					log.warn(String.format("Create new image ? %d != %d (w) %d != %d (h) ", currentRenderingContext.width, reuse.getWidth(), currentRenderingContext.height, reuse.getHeight()));
				}
				if(transparent) {
					// necessary
					bmp = Bitmap.createBitmap(currentRenderingContext.width, currentRenderingContext.height, Config.ARGB_8888);
				} else {
					// better picture ? 
					bmp = Bitmap.createBitmap(currentRenderingContext.width, currentRenderingContext.height, Config.ARGB_8888);
					//bmp = Bitmap.createBitmap(256, 256, Config.ARGB_8888);
				}//这里产生的bmp是背景图，纯色
			}
			
			//将生成的bmp保存成图片格式存储在本地
//			String name = "pic"+picnum+".png";
//			File f =new File("/sdcard/osmpng/",name);
//			try{
//			f.createNewFile();
//			FileOutputStream out = new FileOutputStream(f);
//			bmp.compress(Bitmap.CompressFormat.PNG, 90, out);
//			out.flush();
//			out.close();
//			log.warn("mytag:图片已保存"+picnum);
//			}catch(IOException  e){
//				e.printStackTrace();
//			}
//			picnum = picnum+1;
			
			//
			
			double cBottomLatitude = dataBox.bottom;
			double cTopLatitude = dataBox.top;
			double cLeftLongitude = dataBox.left;
			double cRightLongitude = dataBox.right;
			int leftX = MapUtils.get31TileNumberX(cLeftLongitude);
			int rightX = MapUtils.get31TileNumberX(cRightLongitude);
			int bottomY = MapUtils.get31TileNumberY(cBottomLatitude);
			int topY = MapUtils.get31TileNumberY(cTopLatitude);
			
			int[] coordinantes = new int[]{leftX, topY, rightX, topY, rightX, bottomY, leftX, bottomY, leftX, topY};
			MapIndex nmi = new MapIndex();
			int type[] = {42};
			
			
			for(int i =0;i<cObjects.size();i++){
				BinaryMapDataObject o = cObjects.get(i);
				for(int j=0;j<o.getTypes().length;j++){
					log.warn("test:mytt:"+(j+1)+" "+o.getTypes()[j]);
				}
				if(o.getId()==-2491146){
					cObjects.get(i).setCoordinates(coordinantes);
					cObjects.get(i).setTypes(type);
					//nmi = cObjects.get(i).getMapIndex();
				}else{
					log.warn("mymy there is no data");
				}
				
			}
			
			//cObjects.get(0).setCoordinates(coordinantes);
			cObjects.clear();
			BinaryMapDataObject mo = new BinaryMapDataObject(coordinantes, type, new int[0][],-2491146);
//			
//			log.warn("mymy test"+o.getId());
			mo.setMapIndex(nmi);
			mo.setArea(false);
			cObjects.add(mo);
			
//			cObjects.clear();
//			for(int loop=0;loop<cObjects.size();loop++){
//				BinaryMapDataObject o = cObjects.get(loop);
//				for(int i =0;i<o.getTypes().length;i++){
//					log.warn("mytest"+(loop+1)+"  "+o.getTypes()[i]);
//				}
//				
//				
//			}
//			cObjects.get(0).getTypes();
			//BinaryMapDataObject
			
			this.bmp = bmp;
			this.bmpLocation = tileRect;
		
			if(nativeLib != null) {
				renderer.generateNewBitmapNative(currentRenderingContext, nativeLib, cNativeObjects, bmp, renderingReq, notifyList);
			} else {
				renderer.generateNewBitmap(tileRect,currentRenderingContext, cObjects, bmp, renderingReq, notifyList);
				int objectType = renderingReq.getIntPropertyValue(renderingReq.ALL.R_OBJECT_TYPE);
//				Bitmap tempbmp = Bitmap.createBitmap(256, 256, Config.ARGB_8888);
//				QuadRect testBox = new QuadRect( left,  top,  right,  bottom);
//				loadVectorData(testBox, requestedBox.getZoom(), renderingReq);
//				renderer.generateNewBitmap(tileRect,currentRenderingContext, cObjects, tempbmp, renderingReq, notifyList);
				//在bmp上画出线条及多边形等操作
			}
			
			
			//test
			
			
			// Force to use rendering request in order to prevent Garbage Collector when it is used in C++
			if(renderingReq != null){
				log.info("Debug :" + renderingReq != null);				
			}
			String renderingDebugInfo = currentRenderingContext.renderingDebugInfo;
			currentRenderingContext.ended = true;
			if (checkWhetherInterrupted()) {
				// revert if it was interrupted 
				// (be smart a bit do not revert if road already drawn) 
				if(currentRenderingContext.lastRenderedKey < 35) {
					reuse = this.bmp;
					this.bmp = this.prevBmp;
					this.bmpLocation = this.prevBmpLocation;
					this.prevBmp = reuse;
					this.prevBmpLocation = null;
				}
				currentRenderingContext = null;
				return;
			} else {
				this.checkedRenderedState = renderedState;
				this.checkedBox = this.bmpLocation;
			}
			currentRenderingContext = null;

			// 2. replace whole image
			// keep cache
			// this.prevBmp = null;
			this.prevBmpLocation = null;
			if (prefs.DEBUG_RENDERING_INFO.get() && OsmandPlugin.getEnabledPlugin(OsmandDevelopmentPlugin.class) != null) {
				String timeInfo = "Searching: " + searchTime + " ms"; //$NON-NLS-1$//$NON-NLS-2$//$NON-NLS-3$
				if (renderingDebugInfo != null) {
					timeInfo += "\n" + renderingDebugInfo;
				}
				final String msg = timeInfo;
				log.info(msg);
				handler.post(new Runnable() {
					@Override
					public void run() {
						AccessibleToast.makeText(context, msg, Toast.LENGTH_LONG).show();
					}
				});
			}
		} catch (RuntimeException e) {
			log.error("Runtime memory exception", e); //$NON-NLS-1$
			handler.post(new Runnable() {
				@Override
				public void run() {
					AccessibleToast.makeText(context, R.string.rendering_exception, Toast.LENGTH_SHORT).show();
				}
			});
		} catch (OutOfMemoryError e) {
			log.error("Out of memory error", e); //$NON-NLS-1$
			cObjects = new ArrayList<BinaryMapDataObject>();
			cObjectsBox = new QuadRect();
			handler.post(new Runnable() {
				@Override
				public void run() {
//					ActivityManager activityManager = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
//					ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
//					activityManager.getMemoryInfo(memoryInfo);
//					int avl = (int) (memoryInfo.availMem / (1 << 20));
					int max = (int) (Runtime.getRuntime().maxMemory() / (1 << 20)); 
					int avl = (int) (Runtime.getRuntime().freeMemory() / (1 << 20));
					String s = " (" + avl + " MB available of " + max  + ") ";
					AccessibleToast.makeText(context, context.getString(R.string.rendering_out_of_memory) + s , Toast.LENGTH_SHORT).show();
				}
			});
		} finally {
			if(currentRenderingContext != null) {
				currentRenderingContext.ended = true;
			}
		}

	}
	
	public synchronized void loadMapforTile(RotatedTileBox tileRect, QuadRect testBox, List<IMapDownloaderCallback> notifyList) {

		interrupted = false;
		//test
		
		log.warn("mytag:test：能否正常循环");
			
		
//		int LeftTopTilex = (int)tileRect.getLeftTopTile(tileRect.getZoom()).x;
//		int LeftTopTiley = (int)tileRect.getLeftTopTile(tileRect.getZoom()).y;
//		
//		int RightBottomTilex = (int)tileRect.getRightBottomTile(tileRect.getZoom()).x;
//		int RightBottomTiley = (int)tileRect.getRightBottomTile(tileRect.getZoom()).y;
		
//		log.warn("mytag:test：经度："+tileRect.getLatitude()+"  纬度："+tileRect.getLongitude());
//		log.warn("mytag:test：左上角经纬度："+tileRect.getLeftTopLatLon().getLongitude()+"  "+tileRect.getLeftTopLatLon().getLatitude());
//		log.warn("mytag:test：右下角经纬度："+tileRect.getRightBottomLatLon());
//		
//		log.warn("mytag:test：LeftTopTilex："+LeftTopTilex+"  LeftTopTiley："+LeftTopTiley);
//		log.warn("mytag:test：RightBottomTiley："+RightBottomTilex+"  RightBottomTiley："+RightBottomTiley);
//		
//		int testx =(int)MapUtils.getTileNumberX(tileRect.getZoom(), tileRect.getLongitude());
//		int testy =(int)MapUtils.getTileNumberY(tileRect.getZoom(), tileRect.getLatitude());
//		
//		log.warn("mytag:test：testx："+testx+"  testy："+testy);
		
	    
	    //将获取到的瓦片分布转换成经纬度范围并存储在相应的box中
//		List<QuadRect>  BoxArray = new ArrayList<QuadRect>();
//		int zoom = tileRect.getZoom();
//		for(int tilex=LeftTopTilex;tilex<=RightBottomTilex;tilex++){
//			for(int tiley=LeftTopTiley;tiley<=RightBottomTiley;tiley++){
//				
//				double top = tile2lat(tiley, zoom);
//				double bottom = tile2lat(tiley + 1, zoom);
//				double left = tile2lon(tilex, zoom);
//				double right = tile2lon(tilex + 1, zoom);
//				QuadRect tileBox = new QuadRect( left,  top,  right,  bottom);
//				
//				BoxArray.add(tileBox);
//			}
//			
//		}
//		for(int i=0;i<BoxArray.size();i++){
//			log.warn("mytag:BoxArray"+(i+1)+":  "+BoxArray.get(i));
//		}//
		
		if (currentRenderingContext != null) {
			currentRenderingContext = null;
		}
		try {
			log.warn("counter here!!!");
			// find selected rendering type
			OsmandApplication app = ((OsmandApplication) context.getApplicationContext());
			boolean nightMode = app.getDaynightHelper().isNightMode();
			// boolean moreDetail = prefs.SHOW_MORE_MAP_DETAIL.get();
			RenderingRulesStorage storage = app.getRendererRegistry().getCurrentSelectedRenderer();
			RenderingRuleSearchRequest renderingReq = new RenderingRuleSearchRequest(storage);
			renderingReq.setBooleanFilter(renderingReq.ALL.R_NIGHT_MODE, nightMode);
			for (RenderingRuleProperty customProp : storage.PROPS.getCustomRules()) {
				if (customProp.isBoolean()) {
					CommonPreference<Boolean> pref = prefs.getCustomRenderBooleanProperty(customProp.getAttrName());
					renderingReq.setBooleanFilter(customProp, pref.get());
				} else {
					CommonPreference<String> settings = prefs.getCustomRenderProperty(customProp.getAttrName());
					String res = settings.get();
					if (!Algorithms.isEmpty(res)) {
						if (customProp.isString()) {
							renderingReq.setStringFilter(customProp, res);
						} else if (customProp.isBoolean()) {
							renderingReq.setBooleanFilter(customProp, "true".equalsIgnoreCase(res));
						} else {
							try {
								renderingReq.setIntFilter(customProp, Integer.parseInt(res));
							} catch (NumberFormatException e) {
								e.printStackTrace();
							}
						}
					}
				}
			}
			renderingReq.saveState();
			NativeOsmandLibrary nativeLib = !prefs.SAFE_MODE.get() ? NativeOsmandLibrary.getLibrary(storage, context) : null;

			// prevent editing
			//tileRect.setLatLonCenter(12.12457, 24.4865);
			//QuadRect dataBox = new QuadRect( left,  top,  right,  bottom);
			//tileRect.setLatLon(dataBox);
			//RotatedTileBox temp = new RotatedTileBox(tileRect);
			
			//temp.setLatLon(dataBox);
			//RotatedTileBox latlon = new RotatedTileBox(temp);
			//tileRect.setLatLonCenter(lat, lon)
			//temp.setLatLonCenter((top+bottom)/2, (left+right)/2);//如何获取瓦片中心经纬度
			
//			double lat = temp.getLatFromPixel(temp.getCenterPixelPoint().x, temp.getCenterPixelPoint().y);
//			double lon = temp.getLonFromPixel(temp.getCenterPixelPoint().x, temp.getCenterPixelPoint().y);
			
			//log.warn("mytag:123："+temp.getLeftTopLatLon()+"   "+tileRect.getLeftTopLatLon());
//			temp.setLatLonCenter(latlon.getLatitude(), latlon.getLongitude());
//			log.warn("mytag:latlon.getLatitude()："+latlon.getLatitude());
//			log.warn("mytag:latlon.getLongitude()："+latlon.getLongitude());
//			requestedBox.setLatLon(dataBox);
			
			
			
//			boolean tileloaded;
//			ArrayList<BinaryMapDataObject> tempResult = new ArrayList<BinaryMapDataObject>();
//			for(int i=0;i<BoxArray.size();i++){
//				
//				tileloaded = loadVectorDataforTile(BoxArray.get(i),tempResult, tileRect.getZoom(), renderingReq);
//				
//				if(tileloaded){
//					
//					for(int j=0;j<tempResult.size();j++){
//						subObjects.add(tempResult.get(j));
//					}
//					tempResult.clear();
//					log.warn("mytag:BoxArray"+(i+1)+":成功转置  ");
//				}else{
//					log.warn("mytag:BoxArray"+(i+1)+":未成功转置  ");
//				}
//				
//			}
//			log.warn("mytag:subObjects"+":  "+subObjects.size());
////			
			
			
			requestedBox = new RotatedTileBox(tileRect);
			
			
			requestedBox.getLeftTopLatLon();
			log.warn("mytag:requestedBox.getLeftTopLatLon()："+requestedBox.getLeftTopLatLon());
			
			//requestedBox.getTileBounds();
			
			//requestedBox.setLatLon(testBox);
			
			// calculate data box
//			QuadRect dataBox = requestedBox.getLatLonBounds(); //
			QuadRect dataBox = testBox;
			
			//log.warn("counter here!!!"+dataBox.left);
			 
			long now = System.currentTimeMillis();
			//
			if (cObjectsBox.left > dataBox.left || cObjectsBox.top > dataBox.top || cObjectsBox.right < dataBox.right
					|| cObjectsBox.bottom < dataBox.bottom || (nativeLib != null) == (cNativeObjects == null)) {
				// increase data box in order for rotate
				if ((dataBox.right - dataBox.left) > (dataBox.top - dataBox.bottom)) {
					double wi = (dataBox.right - dataBox.left) * .2;
					dataBox.left -= wi;
					dataBox.right += wi;
				} else {
					double hi = (dataBox.top - dataBox.bottom) * .2;
					dataBox.top += hi;
					dataBox.bottom -= hi;
				}
				validateLatLonBox(dataBox);
				renderedState = 0;
				boolean loaded;
				if(nativeLib != null) {
					cObjects = new LinkedList<BinaryMapDataObject>();
					loaded = loadVectorDataNative(dataBox, requestedBox.getZoom(), renderingReq, nativeLib);
				} else {
					cNativeObjects = null;
					loaded = loadVectorData(dataBox, requestedBox.getZoom(), renderingReq);
					//从本地矢量文件中读取相应信息
					
				}
				if(loaded){
					
					for(int j=0;j<cObjects.size();j++){
						subObjects.add(cObjects.get(j));
					}
					cObjects.clear();
					log.warn("mytag:subObjects"+":成功转置  "+subObjects.size());
				}else{
					log.warn("mytag:subObjects"+":未成功转置  "+subObjects.size());
				}
//				subObjects = cObjects;
				if (!loaded || checkWhetherInterrupted()) {
					return;
				}
			}
			
			//
			
			
			
			//loadVectorData(testBox,requestedBox.getZoom(),renderingReq);
			final long searchTime = System.currentTimeMillis() - now;

			currentRenderingContext = new OsmandRenderer.RenderingContext(context);
			renderingReq.clearState();
			renderingReq.setIntFilter(renderingReq.ALL.R_MINZOOM, requestedBox.getZoom());
			if(renderingReq.searchRenderingAttribute(RenderingRuleStorageProperties.A_DEFAULT_COLOR)) {
				currentRenderingContext.defaultColor = renderingReq.getIntPropertyValue(renderingReq.ALL.R_ATTR_COLOR_VALUE);
			}
			renderingReq.clearState();
			renderingReq.setIntFilter(renderingReq.ALL.R_MINZOOM, requestedBox.getZoom());
			if(renderingReq.searchRenderingAttribute(RenderingRuleStorageProperties.A_SHADOW_RENDERING)) {
				currentRenderingContext.shadowRenderingMode = renderingReq.getIntPropertyValue(renderingReq.ALL.R_ATTR_INT_VALUE);
				currentRenderingContext.shadowRenderingColor = renderingReq.getIntPropertyValue(renderingReq.ALL.R_SHADOW_COLOR);
			}
			if(renderingReq.searchRenderingAttribute("polygonMinSizeToDisplay")) {
				currentRenderingContext.polygonMinSizeToDisplay = renderingReq.getIntPropertyValue(renderingReq.ALL.R_ATTR_INT_VALUE);
			}
			final QuadPointDouble lt = requestedBox.getLeftTopTile(requestedBox.getZoom());
//			LatLon ltn = requestedBox.getLeftTopLatLon();
			final float mapDensity = (float) Math.pow(2, requestedBox.getZoomScale());
			final double tileDivisor = MapUtils.getPowZoom(31 - requestedBox.getZoom() -
						requestedBox.getZoomScale());
			currentRenderingContext.leftX = lt.x * MapUtils.getPowZoom(requestedBox.getZoomScale());
					// MapUtils.get31TileNumberX(ltn.getLongitude()) / tileDivisor;
			currentRenderingContext.topY = lt.y * MapUtils.getPowZoom(requestedBox.getZoomScale());
					//MapUtils.get31TileNumberY(ltn.getLatitude()) / tileDivisor;
			currentRenderingContext.zoom = requestedBox.getZoom();
			currentRenderingContext.rotate = requestedBox.getRotate();
			currentRenderingContext.width = requestedBox.getPixWidth();
			currentRenderingContext.height = requestedBox.getPixHeight();
			
//			currentRenderingContext.width = 256;
//			currentRenderingContext.height = 256;
			currentRenderingContext.nightMode = nightMode;
			currentRenderingContext.preferredLocale = prefs.MAP_PREFERRED_LOCALE.get();
			currentRenderingContext.setDensityValue(mapDensity);
			currentRenderingContext.screenDensityRatio = mapDensity / Math.max(1, requestedBox.getDensity()) ;
			// init rendering context
			currentRenderingContext.tileDivisor = tileDivisor;
			if (checkWhetherInterrupted()) {
				return;
			}

			now = System.currentTimeMillis();
			Bitmap bmp;
			boolean transparent = false;
			RenderingRuleProperty rr = storage.PROPS.get("noPolygons");
			if (rr != null) {
				transparent = renderingReq.getIntPropertyValue(rr) > 0;
			}

			// 1. generate image step by step
			Bitmap reuse = prevBmp;
			this.prevBmp = this.bmp;
			this.prevBmpLocation = this.bmpLocation;
			if (reuse != null && reuse.getWidth() == currentRenderingContext.width && reuse.getHeight() == currentRenderingContext.height) {
				bmp = reuse;
				bmp.eraseColor(currentRenderingContext.defaultColor);
			} else {
				if(reuse != null){
					log.warn(String.format("Create new image ? %d != %d (w) %d != %d (h) ", currentRenderingContext.width, reuse.getWidth(), currentRenderingContext.height, reuse.getHeight()));
				}
				if(transparent) {
					// necessary
					bmp = Bitmap.createBitmap(currentRenderingContext.width, currentRenderingContext.height, Config.ARGB_8888);
				} else {
					// better picture ? 
					bmp = Bitmap.createBitmap(currentRenderingContext.width, currentRenderingContext.height, Config.ARGB_8888);
					//bmp = Bitmap.createBitmap(256, 256, Config.ARGB_8888);
				}//这里产生的bmp是背景图，纯色
			}
			
			
			this.bmp = bmp;
			this.bmpLocation = tileRect;
			
			if(nativeLib != null) {
				renderer.generateNewBitmapNative(currentRenderingContext, nativeLib, cNativeObjects, bmp, renderingReq, notifyList);
			} else {
				//renderer.generateNewBitmap(tileRect,currentRenderingContext, cObjects, bmp, renderingReq, notifyList);
				
			}
			
			
			// Force to use rendering request in order to prevent Garbage Collector when it is used in C++
			if(renderingReq != null){
				log.info("Debug :" + renderingReq != null);				
			}
			String renderingDebugInfo = currentRenderingContext.renderingDebugInfo;
			currentRenderingContext.ended = true;
			if (checkWhetherInterrupted()) {
				// revert if it was interrupted 
				// (be smart a bit do not revert if road already drawn) 
				if(currentRenderingContext.lastRenderedKey < 35) {
					reuse = this.bmp;
					this.bmp = this.prevBmp;
					this.bmpLocation = this.prevBmpLocation;
					this.prevBmp = reuse;
					this.prevBmpLocation = null;
				}
				currentRenderingContext = null;
				return;
			} else {
				this.checkedRenderedState = renderedState;
				this.checkedBox = this.bmpLocation;
			}
			currentRenderingContext = null;

			// 2. replace whole image
			// keep cache
			// this.prevBmp = null;
			this.prevBmpLocation = null;
			if (prefs.DEBUG_RENDERING_INFO.get() && OsmandPlugin.getEnabledPlugin(OsmandDevelopmentPlugin.class) != null) {
				String timeInfo = "Searching: " + searchTime + " ms"; //$NON-NLS-1$//$NON-NLS-2$//$NON-NLS-3$
				if (renderingDebugInfo != null) {
					timeInfo += "\n" + renderingDebugInfo;
				}
				final String msg = timeInfo;
				log.info(msg);
				handler.post(new Runnable() {
					@Override
					public void run() {
						AccessibleToast.makeText(context, msg, Toast.LENGTH_LONG).show();
					}
				});
			}
		} catch (RuntimeException e) {
			log.error("Runtime memory exception", e); //$NON-NLS-1$
			handler.post(new Runnable() {
				@Override
				public void run() {
					AccessibleToast.makeText(context, R.string.rendering_exception, Toast.LENGTH_SHORT).show();
				}
			});
		} catch (OutOfMemoryError e) {
			log.error("Out of memory error", e); //$NON-NLS-1$
			cObjects = new ArrayList<BinaryMapDataObject>();
			cObjectsBox = new QuadRect();
			handler.post(new Runnable() {
				@Override
				public void run() {
//					ActivityManager activityManager = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
//					ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
//					activityManager.getMemoryInfo(memoryInfo);
//					int avl = (int) (memoryInfo.availMem / (1 << 20));
					int max = (int) (Runtime.getRuntime().maxMemory() / (1 << 20)); 
					int avl = (int) (Runtime.getRuntime().freeMemory() / (1 << 20));
					String s = " (" + avl + " MB available of " + max  + ") ";
					AccessibleToast.makeText(context, context.getString(R.string.rendering_out_of_memory) + s , Toast.LENGTH_SHORT).show();
				}
			});
		} finally {
			if(currentRenderingContext != null) {
				currentRenderingContext.ended = true;
			}
		}

	
		
	}
	

	public Bitmap getBitmap() {
		return bmp;
	}

	public Bitmap getPrevBitmap() {
		return prevBmpLocation == null ? null : prevBmp ;
	}

	public synchronized void clearCache() {
		cObjects = new ArrayList<BinaryMapDataObject>();
		cObjectsBox = new QuadRect();

		requestedBox = prevBmpLocation = null;
		// Do not clear main bitmap to not cause a screen refresh
//		prevBmp = null;
//		bmp = null;
//		bmpLocation = null;
	}
	
	public Map<String, BinaryMapIndexReader> getMetaInfoFiles() {
		return files;
	}

	/// MULTI POLYGONS (coastline)
	// returns true if coastlines were added!
	private boolean processCoastlines(List<BinaryMapDataObject> coastLines, int leftX, int rightX, 
			int bottomY, int topY, int zoom, boolean doNotAddIfIncompleted, boolean addDebugIncompleted, List<BinaryMapDataObject> result) {
		List<TLongList> completedRings = new ArrayList<TLongList>();
		List<TLongList> uncompletedRings = new ArrayList<TLongList>();
		MapIndex mapIndex = null;
		long dbId = 0;
		for (BinaryMapDataObject o : coastLines) {
			int len = o.getPointsLength();
			if (len < 2) {
				continue;
			}
			mapIndex = o.getMapIndex();
			dbId = o.getId() >> 1;
			TLongList coordinates = new TLongArrayList(o.getPointsLength() / 2);
			int px = o.getPoint31XTile(0);
			int py = o.getPoint31YTile(0);
			int x = px;
			int y = py;
			boolean pinside = leftX <= x && x <= rightX && y >= topY && y <= bottomY;
			if (pinside) {
				coordinates.add(combine2Points(x, y));
			}
			for (int i = 1; i < len; i++) {
				x = o.getPoint31XTile(i);
				y = o.getPoint31YTile(i);
				boolean inside = leftX <= x && x <= rightX && y >= topY && y <= bottomY;
				boolean lineEnded = calculateLineCoordinates(inside, x, y, pinside, px, py, leftX, rightX, bottomY, topY, coordinates);
				if (lineEnded) {
					combineMultipolygonLine(completedRings, uncompletedRings, coordinates);
					// create new line if it goes outside
					coordinates = new TLongArrayList();
				}
				px = x;
				py = y;
				pinside = inside;
			}
			combineMultipolygonLine(completedRings, uncompletedRings, coordinates);
		}
		if (completedRings.size() == 0 && uncompletedRings.size() == 0) {
			return false;
		}
		if (uncompletedRings.size() > 0) {
			unifyIncompletedRings(uncompletedRings, completedRings, leftX, rightX, bottomY, topY, dbId, zoom);
		}
		long mask = 0xffffffffL;
		// draw uncompleted for debug purpose
		for (int i = 0; i < uncompletedRings.size(); i++) {
			TLongList ring = uncompletedRings.get(i);
			int[] coordinates = new int[ring.size() * 2];
			for (int j = 0; j < ring.size(); j++) {
				coordinates[j * 2] = (int) (ring.get(j) >> 32);
				coordinates[j * 2 + 1] = (int) (ring.get(j) & mask);
			}
			BinaryMapDataObject o = new BinaryMapDataObject(coordinates, new int[] { mapIndex.coastlineBrokenEncodingType }, null, dbId);
			o.setMapIndex(mapIndex);
			result.add(o);
		}
		if(!doNotAddIfIncompleted && uncompletedRings.size() > 0){
			return false;
		}
		boolean clockwiseFound = false;
		
		for (int i = 0; i < completedRings.size(); i++) {
			TLongList ring = completedRings.get(i);
			int[] coordinates = new int[ring.size() * 2];
			for (int j = 0; j < ring.size(); j++) {
				coordinates[j * 2] = (int) (ring.get(j) >> 32);
				coordinates[j * 2 + 1] = (int) (ring.get(j) & mask);
			}
			boolean clockwise = MapAlgorithms.isClockwiseWay(ring);
			clockwiseFound = clockwiseFound || clockwise;
			BinaryMapDataObject o = new BinaryMapDataObject(coordinates, new int[] { clockwise ? mapIndex.coastlineEncodingType
					: mapIndex.landEncodingType }, null, dbId);
			o.setMapIndex(mapIndex);
			o.setArea(true);
			result.add(o);
		}
		
		if (!clockwiseFound && uncompletedRings.size() == 0) {
			// add complete water tile
			BinaryMapDataObject o = new BinaryMapDataObject(new int[] { leftX, topY, rightX, topY, rightX, bottomY, leftX, bottomY, leftX,
					topY }, new int[] { mapIndex.coastlineEncodingType }, null, dbId);
			o.setMapIndex(mapIndex);
			log.info("!!! Isolated islands !!!"); //$NON-NLS-1$
			result.add(o);

		}
		return true;
	}
	
	private boolean eq(long i1, long i2){
		return i1 == i2;
	}
	
	private void combineMultipolygonLine(List<TLongList> completedRings, List<TLongList> incompletedRings,	TLongList coordinates) {
		if (coordinates.size() > 0) {
			if (eq(coordinates.get(0), coordinates.get(coordinates.size() - 1))) {
				completedRings.add(coordinates);
			} else {
				boolean add = true;
				for (int k = 0; k < incompletedRings.size();) {
					boolean remove = false;
					TLongList i = incompletedRings.get(k);
					if (eq(coordinates.get(0), i.get(i.size() - 1))) {
						i.addAll(coordinates.subList(1, coordinates.size()));
						remove = true;
						coordinates = i;
					} else if (eq(coordinates.get(coordinates.size() - 1), i.get(0))) {
						coordinates.addAll(i.subList(1, i.size()));
						remove = true;
					}
					if (remove) {
						incompletedRings.remove(k);
					} else {
						k++;
					}
					if (eq(coordinates.get(0), coordinates.get(coordinates.size() - 1))) {
						completedRings.add(coordinates);
						add = false;
						break;
					}
				}
				if (add) {
					incompletedRings.add(coordinates);
				}
			}
		}
	}

	private void unifyIncompletedRings(List<TLongList> toProcces, List<TLongList> completedRings, int leftX, int rightX, int bottomY, int topY, long dbId, int zoom) {
		int mask = 0xffffffff;
		List<TLongList> uncompletedRings = new ArrayList<TLongList>(toProcces);
		toProcces.clear();
		Set<Integer> nonvisitedRings = new LinkedHashSet<Integer>();
		for (int j = 0; j < uncompletedRings.size(); j++) {
			TLongList i = uncompletedRings.get(j);
			int x = (int) (i.get(i.size() - 1) >> 32);
			int y = (int) (i.get(i.size() - 1) & mask);
			int sx = (int) (i.get(0) >> 32);
			int sy = (int) (i.get(0) & mask);
			boolean st = y == topY || x == rightX || y == bottomY || x == leftX;
			boolean end = sy == topY || sx == rightX || sy == bottomY || sx == leftX;
			// something goes wrong
			// These exceptions are used to check logic about processing multipolygons
			// However this situation could happen because of broken multipolygons (so it should data causes app error)
			// that's why these exceptions could be replaced with return; statement.
			if (!end || !st) {
				float dx = (float) MapUtils.get31LongitudeX(x);
				float dsx = (float) MapUtils.get31LongitudeX(sx);
				float dy = (float) MapUtils.get31LatitudeY(y);
				float dsy = (float) MapUtils.get31LatitudeY(sy);
				String str;
				if (!end) {
					str = " Starting point (to close) not found : end_x = {0}, end_y = {1}, start_x = {2}, start_y = {3} : bounds {4} {5} - {6} {7}"; //$NON-NLS-1$
					System.err
							.println(MessageFormat.format(dbId + str, dx, dy, dsx, dsy, leftX + "", topY + "", rightX + "", bottomY + "")); //$NON-NLS-1$//$NON-NLS-2$//$NON-NLS-3$//$NON-NLS-4$
				}
				if (!st) {
					str = " End not found : end_x = {0}, end_y = {1}, start_x = {2}, start_y = {3} : bounds {4} {5} - {6} {7}"; //$NON-NLS-1$
					System.err
							.println(MessageFormat.format(dbId + str, dx, dy, dsx, dsy, leftX + "", topY + "", rightX + "", bottomY + "")); //$NON-NLS-1$//$NON-NLS-2$//$NON-NLS-3$//$NON-NLS-4$
				}
				toProcces.add(i);
			} else {
				nonvisitedRings.add(j);
			}
		}
		for (int j = 0; j < uncompletedRings.size(); j++) {
			TLongList i = uncompletedRings.get(j);
			if (!nonvisitedRings.contains(j)) {
				continue;
			}

			int x = (int) (i.get(i.size() - 1) >> 32);
			int y = (int) (i.get(i.size() - 1) & mask);
			// 31 - (zoom + 8)
			int EVAL_DELTA = 6 << (23 - zoom);
			int UNDEFINED_MIN_DIFF = -1 - EVAL_DELTA;
			while (true) {
				int st = 0; // st already checked to be one of the four
				if (y == topY) {
					st = 0;
				} else if (x == rightX) {
					st = 1;
				} else if (y == bottomY) {
					st = 2;
				} else if (x == leftX) {
					st = 3;
				}
				int nextRingIndex = -1;
				// BEGIN go clockwise around rectangle
				for (int h = st; h < st + 4; h++) {

					// BEGIN find closest nonvisited start (including current)
					int mindiff = UNDEFINED_MIN_DIFF;
					for (Integer ni : nonvisitedRings) {
						TLongList cni = uncompletedRings.get(ni);
						int csx = (int) (cni.get(0) >> 32);
						int csy = (int) (cni.get(0) & mask);
						if (h % 4 == 0) {
							// top
							if (csy == topY && csx >= safelyAddDelta(x, -EVAL_DELTA)) {
								if (mindiff == UNDEFINED_MIN_DIFF || (csx - x) <= mindiff) {
									mindiff = (csx - x);
									nextRingIndex = ni;
								}
							}
						} else if (h % 4 == 1) {
							// right
							if (csx == rightX && csy >= safelyAddDelta(y, -EVAL_DELTA)) {
								if (mindiff == UNDEFINED_MIN_DIFF || (csy - y) <= mindiff) {
									mindiff = (csy - y);
									nextRingIndex = ni;
								}
							}
						} else if (h % 4 == 2) {
							// bottom
							if (csy == bottomY && csx <= safelyAddDelta(x, EVAL_DELTA)) {
								if (mindiff == UNDEFINED_MIN_DIFF || (x - csx) <= mindiff) {
									mindiff = (x - csx);
									nextRingIndex = ni;
								}
							}
						} else if (h % 4 == 3) {
							// left
							if (csx == leftX && csy <= safelyAddDelta(y, EVAL_DELTA)) {
								if (mindiff == UNDEFINED_MIN_DIFF || (y - csy) <= mindiff) {
									mindiff = (y - csy);
									nextRingIndex = ni;
								}
							}
						}
					} // END find closest start (including current)

					// we found start point
					if (mindiff != UNDEFINED_MIN_DIFF) {
						break;
					} else {
						if (h % 4 == 0) {
							// top
							y = topY;
							x = rightX;
						} else if (h % 4 == 1) {
							// right
							y = bottomY;
							x = rightX;
						} else if (h % 4 == 2) {
							// bottom
							y = bottomY;
							x = leftX;
						} else if (h % 4 == 3) {
							y = topY;
							x = leftX;
						}
						i.add((((long) x) << 32) | ((long) y));
					}

				} // END go clockwise around rectangle
				if (nextRingIndex == -1) {
					// it is impossible (current start should always be found)
				} else if (nextRingIndex == j) {
					i.add(i.get(0));
					nonvisitedRings.remove(j);
					break;
				} else {
					i.addAll(uncompletedRings.get(nextRingIndex));
					nonvisitedRings.remove(nextRingIndex);
					// get last point and start again going clockwise
					x = (int) (i.get(i.size() - 1) >> 32);
					y = (int) (i.get(i.size() - 1) & mask);
				}
			}

			completedRings.add(i);
		}
	}

	private int safelyAddDelta(int number, int delta) {
		int res = number + delta;
		if (delta > 0 && res < number) {
			return Integer.MAX_VALUE;
		} else if (delta < 0 && res > number) {
			return Integer.MIN_VALUE;
		}
		return res;
	}
	
	private static long combine2Points(int x, int y) {
		return (((long) x ) <<32) | ((long)y );
	}
	
	private boolean calculateLineCoordinates(boolean inside, int x, int y, boolean pinside, int px, int py, int leftX, int rightX,
			int bottomY, int topY, TLongList coordinates) {
		boolean lineEnded = false;
		if (pinside) {
			if (!inside) {
				long is = MapAlgorithms.calculateIntersection(x, y, px, py, leftX, rightX, bottomY, topY);
				if (is == -1) {
					// it is an error (!)
					is = combine2Points(px, py);
				}
				coordinates.add(is);
				lineEnded = true;
			} else {
				coordinates.add(combine2Points(x, y));
			}
		} else {
			long is = MapAlgorithms.calculateIntersection(x, y, px, py, leftX, rightX, bottomY, topY);
			if (inside) {
				// assert is != -1;
				coordinates.add(is);
				coordinates.add(combine2Points(x, y));
			} else if (is != -1) {
				int bx = (int) (is >> 32);
				int by = (int) (is & 0xffffffff);
				coordinates.add(is);
				is = MapAlgorithms.calculateIntersection(x, y, bx, by, leftX, rightX, bottomY, topY);
				coordinates.add(is);
				lineEnded = true;
			}
		}

		return lineEnded;
	}

	


}
