package mil.nga.giat.geowave.analytics.clustering;

import java.io.IOException;
import java.util.List;

import mil.nga.giat.geowave.analytics.tools.AnalyticItemWrapper;
import mil.nga.giat.geowave.index.ByteArrayId;

import com.vividsolutions.jts.geom.Coordinate;

/**
 * Manage centroids created per batch and per group of analytic processes. There
 * can be multiple groups per batch. A group is loosely interpreted as a set of
 * item geometries under analysis. The sets can be defined by shared
 * characteristics.
 * 
 * @param <T>
 *            The type of item that is used to represent a centroid.
 */
public interface CentroidManager<T>
{

	/**
	 * Creates a new centroid based on the old centroid with new coordinates and
	 * dimension values
	 * 
	 * @param feature
	 * @param coordinate
	 * @param extraNames
	 * @param extraValues
	 * @return
	 */
	public AnalyticItemWrapper<T> createNextCentroid(
			final T feature,
			final String groupID,
			final Coordinate coordinate,
			final String[] extraNames,
			final double[] extraValues );

	public void delete(
			final String[] dataIds )
			throws IOException;

	public List<String> getAllCentroidGroups()
			throws IOException;

	public List<AnalyticItemWrapper<T>> getCentroidsForGroup(
			final String groupID )
			throws IOException;

	public List<AnalyticItemWrapper<T>> getCentroidsForGroup(
			final String batchID,
			final String groupID )
			throws IOException;
	
	public int processForAllGroups(
			CentroidProcessingFn<T> fn )
			throws IOException;

	public static interface CentroidProcessingFn<T>
	{
		public int processGroup(
				final String groupID,
				final List<AnalyticItemWrapper<T>> centroids );
	}

	public AnalyticItemWrapper<T> getCentroid(
			final String id );

	public void clear();

	public ByteArrayId getDataTypeId();

	public ByteArrayId getIndexId();
}
