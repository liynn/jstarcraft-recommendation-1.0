package com.jstarcraft.recommendation.recommender.collaborative;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.TreeSet;

import com.jstarcraft.ai.math.algorithm.similarity.Similarity;
import com.jstarcraft.ai.math.structure.matrix.MatrixScalar;
import com.jstarcraft.ai.math.structure.matrix.SymmetryMatrix;
import com.jstarcraft.ai.math.structure.vector.DenseVector;
import com.jstarcraft.ai.math.structure.vector.SparseVector;
import com.jstarcraft.core.utility.ReflectionUtility;
import com.jstarcraft.recommendation.configure.Configuration;
import com.jstarcraft.recommendation.data.DataSpace;
import com.jstarcraft.recommendation.data.accessor.InstanceAccessor;
import com.jstarcraft.recommendation.data.accessor.SampleAccessor;
import com.jstarcraft.recommendation.recommender.AbstractRecommender;

/**
 * 
 * User KNN推荐器
 * 
 * <pre>
 * 参考LibRec团队
 * </pre>
 * 
 * @author Birdy
 *
 */
public abstract class UserKNNRecommender extends AbstractRecommender {

	/** 邻居数量 */
	private int neighborSize;

	protected SymmetryMatrix similarityMatrix;

	protected DenseVector userMeans;

	/**
	 * user's nearest neighbors for kNN > 0
	 */
	protected int[][] userNeighbors;

	protected SparseVector[] userVectors;

	protected SparseVector[] itemVectors;

	private Comparator<Entry<Integer, Double>> comparator = new Comparator<Entry<Integer, Double>>() {
		public int compare(Entry<Integer, Double> left, Entry<Integer, Double> right) {
			int value = -(left.getValue().compareTo(right.getValue()));
			if (value == 0) {
				value = left.getKey().compareTo(right.getKey());
			}
			return value;
		}
	};

	@Override
	public void prepare(Configuration configuration, SampleAccessor marker, InstanceAccessor model, DataSpace space) {
		super.prepare(configuration, marker, model, space);
		neighborSize = configuration.getInteger("rec.neighbors.knn.number");
		// TODO 修改为配置枚举
		try {
			Class<Similarity> similarityClass = (Class<Similarity>) Class.forName(configuration.getString("rec.similarity.class"));
			Similarity similarity = ReflectionUtility.getInstance(similarityClass);
			similarityMatrix = similarity.makeSimilarityMatrix(trainMatrix, false, configuration.getFloat("rec.similarity.shrinkage", 0F));
		} catch (Exception exception) {
			throw new RuntimeException(exception);
		}
		userMeans = DenseVector.valueOf(numberOfUsers);

		// TODO 设置容量
		userNeighbors = new int[numberOfUsers][];
		HashMap<Integer, TreeSet<Entry<Integer, Double>>> userNNs = new HashMap<>();
		for (MatrixScalar term : similarityMatrix) {
			int row = term.getRow();
			int column = term.getColumn();
			double value = term.getValue();
			if (row == column) {
				continue;
			}
			// 忽略相似度为0的用户
			if (value == 0D) {
				continue;
			}
			TreeSet<Entry<Integer, Double>> neighbors = userNNs.get(row);
			if (neighbors == null) {
				neighbors = new TreeSet<>(comparator);
				userNNs.put(row, neighbors);
			}
			neighbors.add(new SimpleImmutableEntry<>(column, value));
			neighbors = userNNs.get(column);
			if (neighbors == null) {
				neighbors = new TreeSet<>(comparator);
				userNNs.put(column, neighbors);
			}
			neighbors.add(new SimpleImmutableEntry<>(row, value));
		}

		// 构建用户邻居映射
		for (Entry<Integer, TreeSet<Entry<Integer, Double>>> term : userNNs.entrySet()) {
			TreeSet<Entry<Integer, Double>> neighbors = term.getValue();
			int[] value = new int[neighbors.size() < neighborSize ? neighbors.size() : neighborSize];
			int index = 0;
			for (Entry<Integer, Double> neighbor : neighbors) {
				value[index++] = neighbor.getKey();
				if (index >= neighborSize) {
					break;
				}
			}
			Arrays.sort(value);
			userNeighbors[term.getKey()] = value;
		}

		userVectors = new SparseVector[numberOfUsers];
		for (int userIndex = 0; userIndex < numberOfUsers; userIndex++) {
			userVectors[userIndex] = trainMatrix.getRowVector(userIndex);
		}

		itemVectors = new SparseVector[numberOfItems];
		for (int itemIndex = 0; itemIndex < numberOfItems; itemIndex++) {
			itemVectors[itemIndex] = trainMatrix.getColumnVector(itemIndex);
		}
	}

	@Override
	protected void doPractice() {
		meanOfScore = trainMatrix.getSum(false) / trainMatrix.getElementSize();
		for (int userIndex = 0; userIndex < numberOfUsers; userIndex++) {
			SparseVector userVector = trainMatrix.getRowVector(userIndex);
			userMeans.setValue(userIndex, userVector.getElementSize() > 0 ? userVector.getSum(false) / userVector.getElementSize() : meanOfScore);
		}
	}

}
