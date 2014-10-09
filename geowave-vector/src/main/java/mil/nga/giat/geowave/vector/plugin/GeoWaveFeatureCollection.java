package mil.nga.giat.geowave.vector.plugin;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

import mil.nga.giat.geowave.index.ByteArrayId;
import mil.nga.giat.geowave.store.CloseableIterator;
import mil.nga.giat.geowave.store.adapter.statistics.BoundingBoxDataStatistics;
import mil.nga.giat.geowave.store.adapter.statistics.CountDataStatistics;
import mil.nga.giat.geowave.store.adapter.statistics.DataStatistics;
import mil.nga.giat.geowave.store.query.TemporalConstraints;
import mil.nga.giat.geowave.vector.wms.DistributableRenderer;
import mil.nga.giat.geowave.vector.wms.accumulo.RenderedMaster;

import org.apache.log4j.Logger;
import org.geotools.data.FeatureReader;
import org.geotools.data.Query;
import org.geotools.data.store.DataFeatureCollection;
import org.geotools.factory.Hints;
import org.geotools.feature.FeatureIterator;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.filter.Filter;
import org.opengis.geometry.BoundingBox;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.TransformException;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;

/**
 * This class is a helper for the GeoWave GeoTools data store. It represents a
 * collection of feature data by encapsulating a GeoWave reader and a query
 * object in order to open the appropriate cursor to iterate over data. It uses
 * Keys within the Query hints to determine whether to perform special purpose
 * queries such as decimation or distributed rendering.
 * 
 */
public class GeoWaveFeatureCollection extends
		DataFeatureCollection
{

	public static final Hints.Key LEVEL = new Hints.Key(
			Integer.class);
	public static final Hints.Key SERVER_FEATURE_RENDERER = new Hints.Key(
			DistributableRenderer.class);
	public static final Hints.Key STATS_NAME = new Hints.Key(
			String.class);
	private final static Logger LOGGER = Logger.getLogger(GeoWaveFeatureCollection.class);
	private final GeoWaveFeatureReader reader;
	private CloseableIterator<SimpleFeature> featureCursor;
	private final Query query;
	private static SimpleFeatureType distributedRenderFeatureType;

	public GeoWaveFeatureCollection(
			final GeoWaveFeatureReader reader,
			final Query query ) {
		this.reader = reader;
		this.query = validateQuery(
				GeoWaveFeatureCollection.getSchema(
						reader,
						query).getTypeName(),
				query);
	}

	@Override
	public int getCount() {
		if (query.getFilter().equals(
				Filter.INCLUDE)) {
			// GEOWAVE-60 optimization
			Map<ByteArrayId, DataStatistics<SimpleFeature>> statsMap = reader.getComponents().getDataStatistics(
					reader.getTransaction());
			if (statsMap.containsKey(CountDataStatistics.STATS_ID)) {
				CountDataStatistics stats = (CountDataStatistics) statsMap.get(CountDataStatistics.STATS_ID);
				if (stats != null && stats.isSet()) return (int) stats.getCount();
			}
		}
		else if (query.getFilter().equals(
				Filter.EXCLUDE)) {
			return 0;
		}

		// fallback
		int count = 0;
		try {
			final Iterator<SimpleFeature> iterator = openIterator();
			while (iterator.hasNext()) {
				iterator.next();
				count++;
			}
			close(iterator);
		}
		catch (final Exception e) {
			LOGGER.warn(
					"Error getting count",
					e);
		}
		return count;
	}

	@Override
	public ReferencedEnvelope getBounds() {

		double minx = Double.MAX_VALUE, maxx = -Double.MAX_VALUE, miny = Double.MAX_VALUE, maxy = Double.MAX_VALUE;
		try {
			// GEOWAVE-60 optimization
			Map<ByteArrayId, DataStatistics<SimpleFeature>> statsMap = reader.getComponents().getDataStatistics(
					reader.getTransaction());
			if (statsMap.containsKey(BoundingBoxDataStatistics.STATS_ID)) {
				BoundingBoxDataStatistics<SimpleFeature> stats = (BoundingBoxDataStatistics<SimpleFeature>) statsMap.get(BoundingBoxDataStatistics.STATS_ID);
				return new ReferencedEnvelope(
						stats.getMinX(),
						stats.getMaxX(),
						stats.getMinY(),
						stats.getMaxY(),
						GeoWaveGTDataStore.DEFAULT_CRS);
			}
			final Iterator<SimpleFeature> iterator = openIterator();
			if (!iterator.hasNext()) {
				return null;
			}
			while (iterator.hasNext()) {
				final BoundingBox bbox = iterator.next().getBounds();
				minx = Math.min(
						bbox.getMinX(),
						minx);
				maxx = Math.max(
						bbox.getMaxX(),
						maxx);
				miny = Math.min(
						bbox.getMinY(),
						miny);
				maxy = Math.max(
						bbox.getMaxY(),
						maxy);

			}
			close(iterator);
		}
		catch (final Exception e) {
			LOGGER.warn(
					"Error calculating bounds",
					e);
			return new ReferencedEnvelope(
					-180,
					180,
					-90,
					90,
					GeoWaveGTDataStore.DEFAULT_CRS);
		}
		return new ReferencedEnvelope(
				minx,
				maxx,
				miny,
				maxy,
				GeoWaveGTDataStore.DEFAULT_CRS);

	}

	@Override
	public SimpleFeatureType getSchema() {
		if (isDistributedRenderQuery()) {
			return getDistributedRenderFeatureType();
		}
		return reader.getFeatureType();
	}

	public static synchronized SimpleFeatureType getDistributedRenderFeatureType() {
		if (distributedRenderFeatureType == null) {
			distributedRenderFeatureType = createDistributedRenderFeatureType();
		}
		return distributedRenderFeatureType;
	}

	private static SimpleFeatureType createDistributedRenderFeatureType() {
		final SimpleFeatureTypeBuilder typeBuilder = new SimpleFeatureTypeBuilder();
		typeBuilder.setName("image_type");
		typeBuilder.add(
				"Image",
				RenderedMaster.class);
		return typeBuilder.buildFeatureType();
	}

	protected String getStatsQueryName() {
		final Object statsQueryName = query.getHints().get(
				STATS_NAME);
		if (statsQueryName == null) {
			return null;
		}
		return statsQueryName.toString();
	}

	protected boolean isDistributedRenderQuery() {
		return GeoWaveFeatureCollection.isDistributedRenderQuery(query);
	}

	protected static final boolean isDistributedRenderQuery(
			Query query ) {
		return query.getHints().containsKey(
				SERVER_FEATURE_RENDERER);
	}

	private static SimpleFeatureType getSchema(
			GeoWaveFeatureReader reader,
			Query query ) {
		if (GeoWaveFeatureCollection.isDistributedRenderQuery(query)) {
			return getDistributedRenderFeatureType();
		}
		return reader.getComponents().getAdapter().getType();
	}

	@Override
	protected Iterator<SimpleFeature> openIterator() {
		Geometry jtsBounds;
		TemporalConstraints timeBounds;

		try {
			ReferencedEnvelope referencedEnvelope = this.getEnvelope(query);
			jtsBounds = getBBox(
					query,
					referencedEnvelope);
			timeBounds = getBoundedTime(query);
			Integer limit = getLimit(query);

			if (query.getFilter() == Filter.EXCLUDE) {
				featureCursor = reader.getNoData();
			}
			else if (query.getFilter() == Filter.INCLUDE || (jtsBounds == null && timeBounds == null)) {
				// get all of the data (yikes)
				featureCursor = reader.getAllData(
						query.getFilter(),
						limit);
			}
			else if (isDistributedRenderQuery()) {
				featureCursor = reader.renderData(
						jtsBounds,
						timeBounds,
						query.getFilter(),
						(DistributableRenderer) query.getHints().get(
								SERVER_FEATURE_RENDERER));
			}
			else if (query.getHints().containsKey(
					DecimationProcess.OUTPUT_WIDTH) && query.getHints().containsKey(
					DecimationProcess.OUTPUT_HEIGHT) && query.getHints().containsKey(
					DecimationProcess.OUTPUT_BBOX)) {
				double pixelSize = 1;
				if (query.getHints().containsKey(
						DecimationProcess.PIXEL_SIZE)) {
					pixelSize = (Double) query.getHints().get(
							DecimationProcess.PIXEL_SIZE);
				}
				featureCursor = reader.getData(
						jtsBounds,
						timeBounds,
						(Integer) query.getHints().get(
								DecimationProcess.OUTPUT_WIDTH),
						(Integer) query.getHints().get(
								DecimationProcess.OUTPUT_HEIGHT),
						pixelSize,
						query.getFilter(),
						referencedEnvelope,
						limit);

			}
			else if (getStatsQueryName() != null) {
				featureCursor = reader.getData(
						jtsBounds,
						timeBounds,
						(Integer) query.getHints().get(
								LEVEL),
						(String) query.getHints().get(
								STATS_NAME));
			}
			else {
				// get the data within the bounding box
				featureCursor = reader.getData(
						jtsBounds,
						timeBounds,
						query.getFilter(),
						limit);
			}
		}
		catch (TransformException | FactoryException e) {
			LOGGER.warn(
					"Unable to transform geometry",
					e);
		}
		return featureCursor;
	}

	private ReferencedEnvelope getEnvelope(
			final Query query )
			throws TransformException,
			FactoryException {
		if (query.getHints().containsKey(
				DecimationProcess.OUTPUT_BBOX)) {
			return ((ReferencedEnvelope) query.getHints().get(
					DecimationProcess.OUTPUT_BBOX)).transform(
					GeoWaveGTDataStore.DEFAULT_CRS,
					true);
		}
		return null;
	}

	private Geometry getBBox(
			final Query query,
			ReferencedEnvelope envelope ) {
		if (envelope != null) return new GeometryFactory().toGeometry(envelope);

		final Geometry bbox = (Geometry) query.getFilter().accept(
				ExtractGeometryFilterVisitor.GEOMETRY_VISITOR,
				null);
		if (bbox == null || bbox.isEmpty()) return null;
		final double area = bbox.getArea();
		if (Double.isInfinite(area) || Double.isNaN(area)) {
			return null;
		}

		return bbox;
	}

	private Query validateQuery(
			String typeName,
			Query query ) {
		return query == null ? new Query(
				typeName,
				Filter.EXCLUDE) : query;
	}

	private Integer getLimit(
			Query query ) {
		if (!query.isMaxFeaturesUnlimited() && (query.getMaxFeatures() >= 0)) {
			return query.getMaxFeatures();
		}
		return null;
	}

	private TemporalConstraints getBoundedTime(
			final Query query ) {
		if (query == null) {
			return null;
		}
		final TemporalConstraints bbox = (TemporalConstraints) query.getFilter().accept(
				ExtractTimeFilterVisitor.TIME_VISITOR,
				null);
		return bbox.isEmpty() ? null : bbox;
	}

	@Override
	public FeatureReader<SimpleFeatureType, SimpleFeature> reader() {
		return reader;
	}

	@Override
	protected void closeIterator(
			final Iterator<SimpleFeature> close ) {
		try {
			featureCursor.close();
		}
		catch (final IOException e) {
			LOGGER.warn(
					"Unable to close iterator",
					e);
		}
	}

	public Iterator<SimpleFeature> getOpenIterator() {
		return featureCursor;
	}

	@Override
	public void close(
			final FeatureIterator<SimpleFeature> iterator ) {
		featureCursor = null;
		super.close(iterator);
	}

	@Override
	public boolean isEmpty() {
		try {
			return !reader.hasNext();
		}
		catch (final IOException e) {
			LOGGER.warn(
					"Error checking reader",
					e);
		}
		return true;
	}
}
