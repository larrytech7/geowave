package mil.nga.giat.geowave.analytics.kmeans.mapreduce.runners;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import mil.nga.giat.geowave.analytics.clustering.CentroidManager;
import mil.nga.giat.geowave.analytics.clustering.CentroidManager.CentroidProcessingFn;
import mil.nga.giat.geowave.analytics.clustering.CentroidManagerGeoWave;
import mil.nga.giat.geowave.analytics.tools.AnalyticItemWrapper;
import mil.nga.giat.geowave.analytics.tools.PropertyManagement;
import mil.nga.giat.geowave.analytics.tools.mapreduce.MapReduceJobRunner;

import org.apache.hadoop.conf.Configuration;

/**
 * Determine the number of iterations in the KMeans Parallel initialization
 * step. Each iteration samples a set of K points from the full population. The
 * number of iterations is log(psi) where psi is the initial cost of the system
 * with a single centroid. Rounding is in effect. To obtain a reasonable sample,
 * the minimum is 2.
 * 
 * This class has been adapted to determine the maximum number of iterations
 * required across multiple groups. Each group is its own set of clusters.
 * 
 */
public class IterationCountCalculateRunner<T> implements
		MapReduceJobRunner
{

	private int iterationsCount = 1;

	public IterationCountCalculateRunner() {}


	public int getIterationsCount() {
		return iterationsCount;
	}

	public void setIterationsCount(
			int iterationsCount ) {
		this.iterationsCount = iterationsCount;
	}

	@Override
	public int run(
			final Configuration config,
			final PropertyManagement runTimeProperties )
			throws Exception {
		iterationsCount = this.getIterations(runTimeProperties);

		return 0;
	}

	private int getIterations(
			final PropertyManagement propertyManagement )
			throws Exception {

		final CentroidManager<T> centroidManager = new CentroidManagerGeoWave<T>(
				propertyManagement);

		final AtomicInteger resultHolder = new AtomicInteger(
				0);

		// Must iterate through the worst case.
		centroidManager.processForAllGroups(new CentroidProcessingFn<T>() {
			@Override
			public int processGroup(
					final String groupID,
					final List<AnalyticItemWrapper<T>> centroids ) {
				resultHolder.set(Math.max(
						resultHolder.get(),
						(centroids.size() > 0) ? (int) Math.round(Math.log(centroids.get(
								0).getCost())) : 0));
				return 0;
			}
		});

		return Math.max(
				iterationsCount,
				resultHolder.get());

	}

}