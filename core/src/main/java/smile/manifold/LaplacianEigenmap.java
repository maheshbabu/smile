/*******************************************************************************
 * Copyright (c) 2010-2020 Haifeng Li. All rights reserved.
 *
 * Smile is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * Smile is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Smile.  If not, see <https://www.gnu.org/licenses/>.
 ******************************************************************************/

package smile.manifold;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import smile.data.SparseDataset;
import smile.graph.AdjacencyList;
import smile.graph.Graph.Edge;
import smile.math.distance.Distance;
import smile.math.distance.EuclideanDistance;
import smile.math.matrix.ARPACK;
import smile.math.matrix.Matrix;
import smile.math.matrix.SparseMatrix;
import smile.util.SparseArray;

/**
 * Laplacian Eigenmap. Using the notion of the Laplacian of the nearest
 * neighbor adjacency graph, Laplacian Eigenmap computes a low dimensional
 * representation of the dataset that optimally preserves local neighborhood
 * information in a certain sense. The representation map generated by the
 * algorithm may be viewed as a discrete approximation to a continuous map
 * that naturally arises from the geometry of the manifold.
 * <p>
 * The locality preserving character of the Laplacian Eigenmap algorithm makes
 * it relatively insensitive to outliers and noise. It is also not prone to
 * "short circuiting" as only the local distances are used.
 *
 * @see IsoMap
 * @see LLE
 * @see UMAP
 * 
 * <h2>References</h2>
 * <ol>
 * <li> Mikhail Belkin and Partha Niyogi. Laplacian Eigenmaps and Spectral Techniques for Embedding and Clustering. NIPS, 2001. </li>
 * </ol>
 * 
 * @author Haifeng Li
 */
public class LaplacianEigenmap implements Serializable {
    private static final long serialVersionUID = 2L;

    /**
     * The width of heat kernel.
     */
    public final double width;
    /**
     * The original sample index.
     */
    public final int[] index;
    /**
     * The coordinate matrix in embedding space.
     */
    public final double[][] coordinates;
    /**
     * Nearest neighbor graph.
     */
    public final AdjacencyList graph;

    /**
     * Constructor with discrete weights.
     * @param index the original sample index.
     * @param coordinates the coordinates.
     * @param graph the nearest neighbor graph.
     */
    public LaplacianEigenmap(int[] index, double[][] coordinates, AdjacencyList graph) {
        this(-1, index, coordinates, graph);
    }

    /**
     * Constructor with Gaussian kernel.
     * @param width the width of heat kernel.
     * @param index the original sample index.
     * @param coordinates the coordinates.
     * @param graph the nearest neighbor graph.
     */
    public LaplacianEigenmap(double width, int[] index, double[][] coordinates, AdjacencyList graph) {
        this.width = width;
        this.index = index;
        this.coordinates = coordinates;
        this.graph = graph;
    }

    /**
     * Laplacian Eigenmaps with discrete weights.
     * @param data the input data.
     * @param k k-nearest neighbor.
     */
    public static LaplacianEigenmap of(double[][] data, int k) {
        return of(data, k, 2, -1);
    }

    /**
     * Laplacian Eigenmap with Gaussian kernel.
     * @param data the input data.
     * @param d the dimension of the manifold.
     * @param k k-nearest neighbor.
     * @param t the smooth/width parameter of heat kernel e<sup>-||x-y||<sup>2</sup> / t</sup>.
     *          Non-positive value means discrete weights.
     */
    public static LaplacianEigenmap of(double[][] data, int k, int d, double t) {
        return of(data, new EuclideanDistance(), k, d, t);
    }

    /**
     * Laplacian Eigenmaps with discrete weights.
     * @param data the input data.
     * @param distance the distance measure.
     * @param k k-nearest neighbor.
     */
    public static <T> LaplacianEigenmap of(T[] data, Distance<T> distance, int k) {
        return of(data, distance, k, 2, -1);
    }

    /**
     * Laplacian Eigenmap with Gaussian kernel.
     * @param data the input data.
     * @param distance the distance measure.
     * @param k k-nearest neighbor.
     * @param d the dimension of the manifold.
     * @param t the smooth/width parameter of heat kernel e<sup>-||x-y||<sup>2</sup> / t</sup>.
     *          Non-positive value means discrete weights.
     */
    public static <T> LaplacianEigenmap of(T[] data, Distance<T> distance, int k, int d, double t) {
        // Use largest connected component of nearest neighbor graph.
        AdjacencyList graph = NearestNeighborGraph.of(data, distance, k, false, null);
        NearestNeighborGraph nng = NearestNeighborGraph.largest(graph);

        int[] index = nng.index;
        int n = index.length;
        graph = nng.graph;

        double[] D = new double[n];
        double gamma = -1.0 / t;

        ArrayList<SparseArray> W = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            SparseArray row = new SparseArray();
            Collection<Edge> edges = graph.getEdges(i);
            for (Edge edge : edges) {
                int j = edge.v2;
                if (i == j) j = edge.v1;

                double w = t <= 0 ? 1.0 : Math.exp(gamma * edge.weight * edge.weight);
                row.set(j, w);
                D[i] += w;
            }
            D[i] = 1 / Math.sqrt(D[i]);
            W.add(i, row);
        }

        for (int i = 0; i < n; i++) {
            SparseArray row = W.get(i);
            for (SparseArray.Entry e : row) {
                e.update(-D[i] * e.x * D[e.i]);
            }
            row.set(i, 1.0);
        }

        // Here L is actually I - D^(-1/2) * W * D^(-1/2)
        SparseMatrix L = SparseDataset.of(W, n).toMatrix();

        // ARPACK may not find all needed eigenvalues for k = d + 1.
        // Hack it with 10 * (d + 1).
        Matrix.EVD eigen = ARPACK.syev(L, Math.min(10*(d+1), n-1), ARPACK.SymmWhich.SM);

        Matrix V = eigen.Vr;
        double[][] coordinates = new double[n][d];
        for (int j = d; --j >= 0; ) {
            double norm = 0.0;
            int c = V.ncols() - j - 2;
            for (int i = 0; i < n; i++) {
                double xi = V.get(i, c) * D[i];
                coordinates[i][j] = xi;
                norm += xi * xi;
            }

            norm = Math.sqrt(norm);
            for (int i = 0; i < n; i++) {
                coordinates[i][j] /= norm;
            }
        }

        return new LaplacianEigenmap(t, index, coordinates, graph);
    }
}
