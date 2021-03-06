package mil.nga.giat.geowave.analytics.clustering.mapreduce;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import mil.nga.giat.geowave.accumulo.mapreduce.GeoWaveWritableInputMapper;
import mil.nga.giat.geowave.accumulo.mapreduce.GeoWaveWritableInputReducer;
import mil.nga.giat.geowave.accumulo.mapreduce.input.GeoWaveInputKey;
import mil.nga.giat.geowave.accumulo.mapreduce.output.GeoWaveOutputKey;
import mil.nga.giat.geowave.analytics.clustering.CentroidManager;
import mil.nga.giat.geowave.analytics.clustering.CentroidManagerGeoWave;
import mil.nga.giat.geowave.analytics.clustering.ClusteringUtils;
import mil.nga.giat.geowave.analytics.clustering.NestedGroupCentroidAssignment;
import mil.nga.giat.geowave.analytics.parameters.HullParameters;
import mil.nga.giat.geowave.analytics.tools.AnalyticFeature;
import mil.nga.giat.geowave.analytics.tools.AnalyticItemWrapper;
import mil.nga.giat.geowave.analytics.tools.AnalyticItemWrapperFactory;
import mil.nga.giat.geowave.analytics.tools.ConfigurationWrapper;
import mil.nga.giat.geowave.analytics.tools.Projection;
import mil.nga.giat.geowave.analytics.tools.SimpleFeatureItemWrapperFactory;
import mil.nga.giat.geowave.analytics.tools.SimpleFeatureProjection;
import mil.nga.giat.geowave.analytics.tools.mapreduce.JobContextConfigurationWrapper;
import mil.nga.giat.geowave.index.ByteArrayId;
import mil.nga.giat.geowave.index.StringUtils;
import mil.nga.giat.geowave.store.index.IndexType;
import mil.nga.giat.geowave.vector.adapter.FeatureDataAdapter;

import org.apache.hadoop.io.ObjectWritable;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.log4j.Logger;
import org.geotools.feature.type.BasicFeatureTypes;
import org.opengis.feature.simple.SimpleFeature;

import com.vividsolutions.jts.algorithm.ConvexHull;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;

/**
 * Compute the convex hull over all points associated with each centroid. Each
 * hull is sent to output as a simple features.
 * 
 * Properties:
 * 
 * @formatter:off
 * 
 *                "ConvexHullMapReduce.Hull.DataTypeId" - Id of the data type to
 *                store the the polygons as simple features - defaults to
 *                "convex_hull"
 * 
 *                "ConvexHullMapReduce.Hull.ProjectionClass" - instance of
 *                {@link mil.nga.giat.geowave.analytics.tools.Projection}
 * 
 *                "ConvexHullMapReduce.Hull.IndexId" - The Index ID used for
 *                output simple features.
 * 
 *                "ConvexHullMapReduce.Hull.WrapperFactoryClass" ->
 *                {@link AnalyticItemWrapperFactory} to group and level
 *                associated with each entry
 * 
 * @see mil.nga.giat.geowave.analytics.clustering.NestedGroupCentroidAssignment
 * 
 * @formatter:on
 */
public class ConvexHullMapReduce
{
	protected static final Logger LOGGER = Logger.getLogger(ConvexHullMapReduce.class);

	public static class ConvexHullMap<T> extends
			GeoWaveWritableInputMapper<GeoWaveInputKey, ObjectWritable>
	{

		protected GeoWaveInputKey outputKey = new GeoWaveInputKey();
		private ObjectWritable currentValue;
		private AnalyticItemWrapperFactory<T> itemWrapperFactory;
		private NestedGroupCentroidAssignment<T> nestedGroupCentroidAssigner;

		// Override parent since there is not need to decode the value.
		@Override
		protected void mapWritableValue(
				final GeoWaveInputKey key,
				final ObjectWritable value,
				final Mapper<GeoWaveInputKey, ObjectWritable, GeoWaveInputKey, ObjectWritable>.Context context )
				throws IOException,
				InterruptedException {
			// cached for efficiency since the output is the input object
			// the de-serialized input object is only used for sampling.
			// For simplicity, allow the de-serialization to occur in all cases,
			// even though some sampling
			// functions do not inspect the input object.
			currentValue = value;
			super.mapWritableValue(
					key,
					value,
					context);
		}

		@Override
		protected void mapNativeValue(
				final GeoWaveInputKey key,
				final Object value,
				final org.apache.hadoop.mapreduce.Mapper<GeoWaveInputKey, ObjectWritable, GeoWaveInputKey, ObjectWritable>.Context context )
				throws IOException,
				InterruptedException {

			@SuppressWarnings("unchecked")
			final AnalyticItemWrapper<T> wrapper = itemWrapperFactory.create((T) value);
			outputKey.setAdapterId(key.getAdapterId());
			outputKey.setDataId(new ByteArrayId(
					StringUtils.stringToBinary(nestedGroupCentroidAssigner.getGroupForLevel(wrapper))));
			context.write(
					outputKey,
					currentValue);
		}

		@SuppressWarnings("unchecked")
		@Override
		protected void setup(
				final Mapper<GeoWaveInputKey, ObjectWritable, GeoWaveInputKey, ObjectWritable>.Context context )
				throws IOException,
				InterruptedException {
			super.setup(context);

			final ConfigurationWrapper config = new JobContextConfigurationWrapper(
					context,
					ConvexHullMapReduce.LOGGER);
			try {
				itemWrapperFactory = config.getInstance(
						HullParameters.Hull.WRAPPER_FACTORY_CLASS,
						ConvexHullMapReduce.class,
						AnalyticItemWrapperFactory.class,
						SimpleFeatureItemWrapperFactory.class);

				itemWrapperFactory.initialize(config);
			}
			catch (final Exception e1) {

				throw new IOException(
						e1);
			}

			try {
				nestedGroupCentroidAssigner = new NestedGroupCentroidAssignment<T>(
						config);
			}
			catch (final Exception e1) {
				throw new IOException(
						e1);
			}
		}
	}

	public static class ConvexHullReducer<T> extends
			GeoWaveWritableInputReducer<GeoWaveOutputKey, SimpleFeature>
	{

		private CentroidManager<T> centroidManager;
		private ByteArrayId indexId;
		private FeatureDataAdapter outputAdapter;
		private Projection<T> projectionFunction;
		/*
		 * Logic inspired by SpatialHadoop convexHullStream method
		 */
		// absolute point cloud limit
		private final int pointCloudThreshold = 50000000;

		private final List<Coordinate> batchCoords = new ArrayList<Coordinate>(
				10000);

		@Override
		protected void reduceNativeValues(
				final GeoWaveInputKey key,
				final Iterable<Object> values,
				final Reducer<GeoWaveInputKey, ObjectWritable, GeoWaveOutputKey, SimpleFeature>.Context context )
				throws IOException,
				InterruptedException {
			// limit on new points per convex hull run (batch)
			int batchThreshold = 10000;

			batchCoords.clear();

			Geometry currentHull = null;

			final String groupID = StringUtils.stringFromBinary(key.getDataId().getBytes());
			final AnalyticItemWrapper<T> centroid = centroidManager.getCentroid(groupID);
			for (final Object value : values) {
				currentHull = null;
				@SuppressWarnings("unchecked")
				final Geometry geo = projectionFunction.getProjection((T) value);
				final Coordinate[] coords = geo.getCoordinates();
				if ((coords.length + batchCoords.size()) > pointCloudThreshold) {
					break;
				}
				for (final Coordinate coordinate : coords) {
					batchCoords.add(coordinate);
				}
				if (coords.length > batchThreshold) {
					batchThreshold = coords.length;
				}
				if (batchCoords.size() > batchThreshold) {
					currentHull = compress(
							key,
							batchCoords);
				}
			}
			currentHull = (currentHull == null) ? compress(
					key,
					batchCoords) : currentHull;

			if (ConvexHullMapReduce.LOGGER.isTraceEnabled()) {
				ConvexHullMapReduce.LOGGER.trace(centroid.getGroupID() + " contains " + groupID);
			}

			final SimpleFeature newPolygonFeature = AnalyticFeature.createGeometryFeature(
					outputAdapter.getType(),
					centroid.getBatchID(),
					UUID.randomUUID().toString(),
					centroid.getName(),
					centroid.getGroupID(),
					centroid.getCost(),
					currentHull,
					new String[0],
					new double[0],
					centroid.getZoomLevel(),
					centroid.getIterationID(),
					centroid.getAssociationCount());
			// new center
			context.write(
					new GeoWaveOutputKey(
							outputAdapter.getAdapterId(),
							indexId),
					newPolygonFeature);
		}

		private static <T> Geometry compress(
				final GeoWaveInputKey key,
				final List<Coordinate> batchCoords ) {
			final Coordinate[] actualCoords = batchCoords.toArray(new Coordinate[batchCoords.size()]);

			// generate convex hull for current batch of points
			final ConvexHull convexHull = new ConvexHull(
					actualCoords,
					new GeometryFactory());
			final Geometry hullGeometry = convexHull.getConvexHull();

			final Coordinate[] hullCoords = hullGeometry.getCoordinates();
			batchCoords.clear();
			for (final Coordinate hullCoord : hullCoords) {
				batchCoords.add(hullCoord);
			}

			return hullGeometry;
		}

		@SuppressWarnings("unchecked")
		@Override
		protected void setup(
				final Reducer<GeoWaveInputKey, ObjectWritable, GeoWaveOutputKey, SimpleFeature>.Context context )
				throws IOException,
				InterruptedException {

			final ConfigurationWrapper config = new JobContextConfigurationWrapper(
					context);
			super.setup(context);
			try {
				centroidManager = new CentroidManagerGeoWave<T>(
						config);
			}
			catch (final Exception e) {
				ConvexHullMapReduce.LOGGER.warn(
						"Unable to initialize centroid manager",
						e);
				throw new IOException(
						"Unable to initialize centroid manager");
			}

			try {
				projectionFunction = config.getInstance(
						HullParameters.Hull.PROJECTION_CLASS,
						ConvexHullMapReduce.class,
						Projection.class,
						SimpleFeatureProjection.class);

				projectionFunction.initialize(config);
			}
			catch (final Exception e1) {
				throw new IOException(
						e1);
			}

			final String polygonDataTypeId = config.getString(
					HullParameters.Hull.DATA_TYPE_ID,
					ConvexHullMapReduce.class,
					"convex_hull");

			outputAdapter = AnalyticFeature.createGeometryFeatureAdapter(
					polygonDataTypeId,
					new String[0],
					config.getString(
							HullParameters.Hull.DATA_NAMESPACE_URI,
							ConvexHullMapReduce.class,
							BasicFeatureTypes.DEFAULT_NAMESPACE),
							ClusteringUtils.CLUSTERING_CRS);

			indexId = new ByteArrayId(
					StringUtils.stringToBinary(config.getString(
							HullParameters.Hull.INDEX_ID,
							ConvexHullMapReduce.class,
							IndexType.SPATIAL_VECTOR.getDefaultId())));

		}
	}

}
