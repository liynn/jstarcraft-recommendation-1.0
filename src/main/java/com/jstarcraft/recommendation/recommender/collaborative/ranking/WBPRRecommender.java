package com.jstarcraft.recommendation.recommender.collaborative.ranking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import com.jstarcraft.ai.math.structure.DefaultScalar;
import com.jstarcraft.ai.math.structure.MathCalculator;
import com.jstarcraft.ai.math.structure.vector.DenseVector;
import com.jstarcraft.ai.math.structure.vector.SparseVector;
import com.jstarcraft.core.utility.KeyValue;
import com.jstarcraft.core.utility.RandomUtility;
import com.jstarcraft.recommendation.configure.Configuration;
import com.jstarcraft.recommendation.data.DataSpace;
import com.jstarcraft.recommendation.data.accessor.InstanceAccessor;
import com.jstarcraft.recommendation.data.accessor.SampleAccessor;
import com.jstarcraft.recommendation.recommender.MatrixFactorizationRecommender;
import com.jstarcraft.recommendation.utility.LogisticUtility;

import it.unimi.dsi.fastutil.ints.IntSet;

/**
 * 
 * WBPR推荐器
 * 
 * <pre>
 * Bayesian Personalized Ranking for Non-Uniformly Sampled Items
 * 参考LibRec团队
 * </pre>
 * 
 * @author Birdy
 *
 */
public class WBPRRecommender extends MatrixFactorizationRecommender {
	/**
	 * user items Set
	 */
	// private LoadingCache<Integer, IntSet> userItemsSet;

	/**
	 * pre-compute and sort by item's popularity
	 */
	private List<KeyValue<Integer, Double>> itemPopularities;

	private List<KeyValue<Integer, Double>>[] itemProbabilities;

	/**
	 * items biases
	 */
	private DenseVector itemBiases;

	/**
	 * bias regularization
	 */
	private float biasRegularization;

	/**
	 * Guava cache configuration
	 */
	// protected static String cacheSpec;

	@Override
	public void prepare(Configuration configuration, SampleAccessor marker, InstanceAccessor model, DataSpace space) {
		super.prepare(configuration, marker, model, space);
		biasRegularization = configuration.getFloat("rec.bias.regularization", 0.01F);

		itemBiases = DenseVector.valueOf(numberOfItems);
		itemBiases.iterateElement(MathCalculator.SERIAL, (scalar) -> {
			scalar.setValue(RandomUtility.randomFloat(0.01F));
		});

		// pre-compute and sort by item's popularity
		itemPopularities = new ArrayList<>(numberOfItems);
		for (int itemIndex = 0; itemIndex < numberOfItems; itemIndex++) {
			itemPopularities.add(new KeyValue<>(itemIndex, Double.valueOf(trainMatrix.getColumnScope(itemIndex))));
		}
		Collections.sort(itemPopularities, (left, right) -> {
			// 降序
			return right.getValue().compareTo(left.getValue());
		});

		itemProbabilities = new List[numberOfUsers];
		List<IntSet> userItemSet = getUserItemSet(trainMatrix);
		for (int userIndex = 0; userIndex < numberOfUsers; userIndex++) {
			IntSet scoreSet = userItemSet.get(userIndex);
			List<KeyValue<Integer, Double>> probabilities = new LinkedList<>();
			itemProbabilities[userIndex] = probabilities;
			// filter candidate items
			double sum = 0;
			for (KeyValue<Integer, Double> term : itemPopularities) {
				int itemIndex = term.getKey();
				double popularity = term.getValue();
				if (!scoreSet.contains(itemIndex) && popularity > 0D) {
					// make a clone to prevent bugs from normalization
					probabilities.add(term);
					sum += popularity;
				}
			}
			// normalization
			for (KeyValue<Integer, Double> term : probabilities) {
				term.setValue(term.getValue() / sum);
			}
		}
	}

	@Override
	protected void doPractice() {
		for (int iterationStep = 1; iterationStep <= numberOfEpoches; iterationStep++) {
			totalLoss = 0F;
			for (int sampleIndex = 0, sampleTimes = numberOfUsers * 100; sampleIndex < sampleTimes; sampleIndex++) {
				// randomly draw (userIdx, posItemIdx, negItemIdx)
				int userIndex, positiveItemIndex, negativeItemIndex = 0;
				List<KeyValue<Integer, Double>> probabilities;
				while (true) {
					userIndex = RandomUtility.randomInteger(numberOfUsers);
					SparseVector userVector = trainMatrix.getRowVector(userIndex);
					if (userVector.getElementSize() == 0) {
						continue;
					}
					positiveItemIndex = userVector.getIndex(RandomUtility.randomInteger(userVector.getElementSize()));
					// sample j by popularity (probability)
					probabilities = itemProbabilities[userIndex];
					double random = RandomUtility.randomDouble(1D);
					for (KeyValue<Integer, Double> term : probabilities) {
						if ((random -= term.getValue()) <= 0D) {
							negativeItemIndex = term.getKey();
							break;
						}
					}
					break;
				}

				// update parameters
				float positiveRate = predict(userIndex, positiveItemIndex);
				float negativeRate = predict(userIndex, negativeItemIndex);
				float error = positiveRate - negativeRate;
				float value = (float) -Math.log(LogisticUtility.getValue(error));
				totalLoss += value;
				value = LogisticUtility.getValue(-error);

				// update bias
				float positiveBias = itemBiases.getValue(positiveItemIndex), negativeBias = itemBiases.getValue(negativeItemIndex);
				itemBiases.shiftValue(positiveItemIndex, learnRate * (value - biasRegularization * positiveBias));
				itemBiases.shiftValue(negativeItemIndex, learnRate * (-value - biasRegularization * negativeBias));
				totalLoss += biasRegularization * (positiveBias * positiveBias + negativeBias * negativeBias);

				// update user/item vectors
				for (int factorIndex = 0; factorIndex < numberOfFactors; factorIndex++) {
					float userFactor = userFactors.getValue(userIndex, factorIndex);
					float positiveItemFactor = itemFactors.getValue(positiveItemIndex, factorIndex);
					float negativeItemFactor = itemFactors.getValue(negativeItemIndex, factorIndex);
					userFactors.shiftValue(userIndex, factorIndex, learnRate * (value * (positiveItemFactor - negativeItemFactor) - userRegularization * userFactor));
					itemFactors.shiftValue(positiveItemIndex, factorIndex, learnRate * (value * userFactor - itemRegularization * positiveItemFactor));
					itemFactors.shiftValue(negativeItemIndex, factorIndex, learnRate * (value * (-userFactor) - itemRegularization * negativeItemFactor));
					totalLoss += userRegularization * userFactor * userFactor + itemRegularization * positiveItemFactor * positiveItemFactor + itemRegularization * negativeItemFactor * negativeItemFactor;
				}
			}
			if (isConverged(iterationStep) && isConverged) {
				break;
			}
			isLearned(iterationStep);
			currentLoss = totalLoss;
		}
	}

	@Override
	protected float predict(int userIndex, int itemIndex) {
		DefaultScalar scalar = DefaultScalar.getInstance();
		DenseVector userVector = userFactors.getRowVector(userIndex);
		DenseVector itemVector = itemFactors.getRowVector(itemIndex);
		return itemBiases.getValue(itemIndex) + scalar.dotProduct(userVector, itemVector).getValue();
	}

	@Override
	public float predict(int[] dicreteFeatures, float[] continuousFeatures) {
		int userIndex = dicreteFeatures[userDimension];
		int itemIndex = dicreteFeatures[itemDimension];
		return predict(userIndex, itemIndex);
	}

}
