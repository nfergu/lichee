package lineage;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import edu.uci.ics.jung.graph.DirectedGraph;
import edu.uci.ics.jung.graph.DirectedSparseGraph;
import edu.uci.ics.jung.graph.util.EdgeType;
import SMutH.TreeVisualizer;
import lineage.AAFClusterer.Cluster;

/**
 * PHYGraph is a directed constraint graph representing the phylogenetic relationship
 * among sample sub-populations.
 * Each internal node in the graph represents a sub-population. 
 * The leaves of the graph represent input cancer samples.
 * A directed edge between two nodes denotes the 'happened-before' evolutionary 
 * relationship between the two nodes.
 */
public class PHYGraph {

	/** Nodes in the graph divided by levels (number of samples node SNPs occurred in) */
	private HashMap<Integer, ArrayList<PHYNode>> nodes;
	/** Nodes in the graph indexed by ID */
	private HashMap<Integer, PHYNode> nodesById;
	
	/** Adjacency map of nodes to the their neighbors */
	private HashMap<PHYNode, ArrayList<PHYNode>> edges;
	
	/** Total number of nodes in the graph */
	private int numNodes;
	
	/** Total number of edges in the graph */
	private int numEdges;
	
	/** Total number of cancer samples */
	private int numSamples;
	
	/** Debugging-only */
	protected int nodeCounter = 0;
	
	/** Maximum AAF (used for the root node) */
	private static final double AAF_MAX = 0.5;
	
	/**
	 * Constructs a PHYGraph from the sub-populations of the SNV groups
	 */
	public PHYGraph(ArrayList<SNPGroup> groups, int totalNumSamples) {
		numSamples = totalNumSamples;
		nodes = new HashMap<Integer, ArrayList<PHYNode>>();
		nodesById = new HashMap<Integer, PHYNode>();
		edges = new HashMap<PHYNode, ArrayList<PHYNode>>(); 
	
		// add root node
		PHYNode root = new PHYNode();
		addNode(root, numSamples+1);
				
		// add group sub-population nodes
		for(SNPGroup g : groups) {
			PHYNode[] groupNodes = new PHYNode[g.getSubPopulations().length];
			for(int i = 0; i < groupNodes.length; i++) {
				PHYNode node = new PHYNode(g, i, g.getNumSamples());
				addNode(node, g.getNumSamples());
				groupNodes[i] = node;
			}
			// add edges between each group's sub-population nodes
			for(int i = 0; i < groupNodes.length; i++) {
				for(int j = i+1; j <  groupNodes.length; j++) {
					checkAndAddEdge(groupNodes[i], groupNodes[j]);
				}
			}
		}
		
		// add sample leaf nodes
		/*for(int i = 0; i < numSamples; i++) {
			PHYNode sampleLeaf = new PHYNode(i);
			addNode(sampleLeaf, 0);
			// add edges from the root to the samples without any mutations
			// in order to have a connected graph
			if(sampleMutationMask[i] == 1) {
				addEdge(root, sampleLeaf);
			}
		}*/
	
		
		// add inter-level edges
		for(int i = numSamples + 1; i > 0; i--) {
			ArrayList<PHYNode> fromLevelNodes = nodes.get(i);
			if(fromLevelNodes == null) continue;
			// find the next non-empty level
			int j = i-1;
			ArrayList<PHYNode> toLevelNodes = nodes.get(j);
			while((toLevelNodes == null) && (j > 0)) {
				j--;
				toLevelNodes = nodes.get(j);
			}
			if(toLevelNodes == null) continue;
			for(PHYNode n1 : fromLevelNodes) {
				for(PHYNode n2: toLevelNodes) {
					checkAndAddEdge(n1, n2);
				}
			}
		}
		
		// find the nodes that are not connected and connect them to a valid node in the closest
		// higher level
		int[] nodeMask = new int[numNodes];
		for(PHYNode n : edges.keySet()) {
			for(PHYNode m : edges.get(n)) {
				nodeMask[m.getNodeId()] = 1;
			}
		}
		
		// skips the root
		for(int i = 1; i < nodeMask.length; i++) {
			if(nodeMask[i] == 0) {
				PHYNode n = nodesById.get(i);			
				// find a parent in the closest higher level
				boolean found = false;
				for(int j = n.getLevel() + 2; j <= numSamples + 1; j++) {
					ArrayList<PHYNode> fromLevelNodes = nodes.get(j);
					if(fromLevelNodes == null) continue;
					for(PHYNode n2 : fromLevelNodes) {
						if(checkAndAddEdge(n2, n) == 0) {
							// found a parent
							found = true;
							break;
						}
					}
					if(found) break;
				}
				if(!found) {
					addEdge(root, n);
				}
			}
		}
	}
	
	// ---- Graph Construction ----
	
	/**
	 * Checks if an edge should be added between two nodes in the network based on the AAF data.
	 * If yes, it adds the edge in the appropriate direction.
	 * The edge is added in the direction that minimizes the error
	 * @requires n1 to be at an equal or higher level than n2
	 * @param n1 - node 1
	 * @param n2 - node 2
	 */
	public int checkAndAddEdge(PHYNode n1, PHYNode n2) {
		if(n2.isLeaf) {
			int sampleId = n2.getLeafSampleId();
			if(n1.getAAF(sampleId) > 0) {
				addEdge(n1, n2);
				return 0;
			}
			return -1;
		}
		
		int comp_12 = 0;
		int comp_21 = 0;
		double err_12 = 0;
		double err_21 = 0;
		
		for(int i = 0; i < numSamples; i++) {
			if((n1.getAAF(i) == 0) && (n2.getAAF(i) != 0)) break;
			comp_12 += (n1.getAAF(i) >= (n2.getAAF(i) - Parameters.AAF_ERROR_MARGIN)) ? 1 : 0;
			if(n1.getAAF(i) < n2.getAAF(i)) {
				err_12 += n2.getAAF(i) - n1.getAAF(i);
			}
		}
		for(int i = 0; i < numSamples; i++) {
			if((n2.getAAF(i) == 0) && (n1.getAAF(i) != 0)) break;
			comp_21 += (n2.getAAF(i) >= (n1.getAAF(i) - Parameters.AAF_ERROR_MARGIN)) ? 1 : 0;
			if(n2.getAAF(i) < n1.getAAF(i)) {
				err_21 += n1.getAAF(i) - n2.getAAF(i);
			}
		}
		
		if(comp_12 == numSamples) {
			if (comp_21 == numSamples) {
				if(err_12 < err_21) {
					addEdge(n1, n2);
					return 0;
				} else {
					addEdge(n2, n1);
					return 1;
				}
			} else {
				addEdge(n1, n2);
				return 0;
			}
		} else if(comp_21 == numSamples) {
			addEdge(n2, n1);
			return 1;
		}
		
		return -1;
	}
	
	/** Adds a new node to the graph */
	public void addNode(PHYNode node, int level) {
		ArrayList<PHYNode> nodeList = nodes.get(level);
		if(nodeList == null) {
			nodes.put(level, new ArrayList<PHYNode>());
		}
		nodes.get(level).add(node);
		nodesById.put(node.getNodeId(), node);
		numNodes++;
	}
	
	/** Adds a new edge to the graph */
	public void addEdge(PHYNode from, PHYNode to) {
		ArrayList<PHYNode> nbrs = edges.get(from);
		if(nbrs == null) {
			edges.put(from, new ArrayList<PHYNode>());
		}
		if(!edges.get(from).contains(to)) {
			edges.get(from).add(to);
			numEdges++;
		}
	}
	
	/** Removes a node from the graph */
	public void removeNode(PHYNode n) {
		
	}
	
	/** Removes an edge from the graph */
	public void removeEdge(PHYNode from, PHYNode to) {
		ArrayList<PHYNode> nbrs = edges.get(from);
		if(nbrs != null) {
			for(PHYNode n : nbrs) {
				if(n.equals(to)) {
					nbrs.remove(n);
					break;
				}
			}
		}
	}
	
	/** Adds all the inter-level edges */
	public void addAllHiddenEdges() {
		// add all inter-level edges
		for(int i = numSamples + 1; i > 0; i--) {
			ArrayList<PHYNode> fromLevelNodes = nodes.get(i);
			if(fromLevelNodes == null) continue;
			for(int j = i-1; j > 0; j--) {
				ArrayList<PHYNode> toLevelNodes = nodes.get(j);
				if(toLevelNodes == null) continue;
				for(PHYNode n1 : fromLevelNodes) {
					for(PHYNode n2: toLevelNodes) {
						checkAndAddEdge(n1, n2);
					}
				}
			}
		}
	}
	
	/**
	 * The network needs to be adjusted when no valid spanning trees are found.
	 * Adjustments include: 
	 * - removing nodes corresponding to groups that are less robust
	 * - adding hidden edges (to do)
	 */
	public PHYGraph fixNetwork() {
		// reconstruct the network from robust groups only
		Set<SNPGroup> filteredGroups = new HashSet<SNPGroup>();
		for(PHYNode n : nodesById.values()) {
			SNPGroup group = n.snpGroup;
			if((group != null) && group.isRobust()) {
				filteredGroups.add(n.snpGroup);
			}
		}
		
		return new PHYGraph(new ArrayList<SNPGroup>(filteredGroups), numSamples);
	}
	
	/** Displays the constraint network graph */
	public void displayNetwork() {
		DirectedGraph<Integer, Integer> g = new DirectedSparseGraph<Integer, Integer>();
		HashMap<Integer, String> nodeLabels = new HashMap<Integer, String>();
		HashMap<Integer, String> edgeLabels = new HashMap<Integer, String>();
			
		int edgeId = 0;
		for (PHYNode n : edges.keySet()) {
			g.addVertex(n.getNodeId());
			nodeLabels.put(n.getNodeId(), n.getLabel());
			for(PHYNode n2 : edges.get(n)) {
				if(!g.containsVertex(n2.getNodeId())) {
					g.addVertex(n2.getNodeId());
					nodeLabels.put(n2.getNodeId(), n2.getLabel());
				}
				g.addEdge(edgeId, n.getNodeId(), n2.getNodeId(), EdgeType.DIRECTED);
				edgeId++;
			}
		}
		new TreeVisualizer(g, nodeLabels, edgeLabels);	
	}
	
	
	// ---- Spanning Tree Generation ----
	
	// based on the algorithm from Gabow & Myers '78
	
	/** List of all generated spanning trees */
	private ArrayList<Tree> spanningTrees;
	
	/** Stack of edges directed from vertices in T to vertices not in T */
	private ArrayList<PHYEdge> f;
	
	/** The last spanning tree output so far */
	private Tree L;
	
	/**
	 * Finds all spanning trees rooted at r
	 */
	public void grow(Tree t) {
		// if the tree t contains all the nodes, it is complete
		if(t.treeNodes.size() == numNodes) {
			L = t;
			spanningTrees.add(L.clone());
			if(spanningTrees.size() % 10000 == 0) {
				System.out.println("Found " + spanningTrees.size() + " trees so far");
			}
		} else {
			// list used to reconstruct the original F
			ArrayList<PHYEdge> ff = new ArrayList<PHYEdge>();
			
			boolean b = false;
			while(!b && (f.size() > 0)) {
				// new tree edge
				PHYEdge e = f.remove(f.size() - 1);
				PHYNode v = e.to;
				t.addNode(v);
				t.addEdge(e.from, v);
				
				// update f
				ArrayList<PHYEdge> edgesAdded = new ArrayList<PHYEdge>();
				ArrayList<PHYNode> vNbrs = edges.get(v);
				if(vNbrs != null) {
					for(PHYNode w : vNbrs) {
						if(!t.containsNode(w)) {
							PHYEdge vw = new PHYEdge(v, w);
							f.add(vw);
							edgesAdded.add(vw);
						}
					}
				}
				
				// remove (w,v) w in T from f
				ArrayList<PHYEdge> edgesRemoved = new ArrayList<PHYEdge>();
				for(int i = 0; i < f.size(); i++) {
					PHYEdge wv = f.get(i);
					if(t.containsNode(wv.from) && (wv.to.equals(v))) {
						edgesRemoved.add(wv);
					}
				}
				f.removeAll(edgesRemoved);
	
				// recurse
				grow(t);
				
				// pop
				f.removeAll(edgesAdded);
				
				// restore
				f.addAll(edgesRemoved);
				
				// remove e from T and G
				t.removeEdge(e.from, e.to);
				this.removeEdge(e.from, e.to);
				
				// add e to FF
				ff.add(e);
				
				// bridge test
				for(PHYNode w : this.edges.keySet()) {
					ArrayList<PHYNode> wNbrs = this.edges.get(w);
					if(wNbrs == null) continue;
					for(PHYNode n : wNbrs) {
						if(n.equals(v)) {
							// check if w is a descendant of v in L
							if(!L.isDescendent(v, w)) {
								b = false;
								break;
							}
						}
					}
					if(!b) break;
				}
			}
			
			// pop from ff, push to f, add to G
			for(int i = ff.size()-1; i >=0; i--) {
				PHYEdge e = ff.get(i);
				f.add(e);
				this.addEdge(e.from, e.to);
			}
			ff.clear();
		}
	}
	
	/**
	 * Generates all the spanning trees from the constraint network
	 * that pass the AAF constraints
	 */
	public ArrayList<Tree> getLineageTrees() {
		spanningTrees = new ArrayList<Tree>();
		
		PHYNode root = nodes.get(numSamples+1).get(0);
		// initialize tree t to contain the root
		Tree t = new Tree();
		t.addNode(root);
		// initialize f to contain all edges (root, v)
		f = new ArrayList<PHYEdge>();
		for(PHYNode n : edges.get(root)) {
			f.add(new PHYEdge(root, n));
			
		}
		grow(t);
		return spanningTrees;
	}
	
	/** 
	 * Evaluate the spanning trees
	 * by computing their error score
	 * and ranking them by this score (lowest error first)
	 */
	public void evaluateSpanningTrees() {
		Collections.sort(spanningTrees);
	}
	
	/**
	 * Apply the AAF constraints
	 */
	public void filterSpanningTrees() {
		ArrayList<Tree> toBeRemoved = new ArrayList<Tree>();
		for(Tree t : spanningTrees) {
			if(!checkAAFConstraints(t)) {
				toBeRemoved.add(t);
			}
		}
		spanningTrees.removeAll(toBeRemoved);
	}
	
	/**
	 * Returns true if the tree passes the AAF constraints
	 * @param t - spanning tree
	 */
	public boolean checkAAFConstraints(Tree t) {
		for(PHYNode n : t.treeEdges.keySet()) {
			ArrayList<PHYNode> nbrs = t.treeEdges.get(n);			
			for(int i = 0; i < numSamples; i++) {
				double affSum = 0;
				for(PHYNode n2 : nbrs) {
					affSum += n2.getAAF(i);
				}
				if(affSum >= n.getAAF(i) + Parameters.AAF_ERROR_MARGIN * nbrs.size()) {
					return false;
				}
			}
		}
		return true;
	}
	
	/**
	 * Adds back "hidden edges" s.t. the constraint is not violated
	 */
	public boolean fixTree(Tree t) {
		// traverse the tree starting at the root
		PHYNode root = nodesById.get(0);
		root.ancestorsWithCapacity = new PHYNode[numSamples];
		
		ArrayList<PHYNode> nodes = new ArrayList<PHYNode>();
		nodes.add(root);

		while(nodes.size() > 0) {
			PHYNode n = nodes.remove(0);
			ArrayList<PHYNode> nbrs = t.treeEdges.get(n);			
			
			// initialize ancestor pointers
			for(PHYNode n2 : nbrs) {
				n2.ancestorsWithCapacity = new PHYNode[numSamples];
			}

			// check the AAF constraint
			for(int i = 0; i < numSamples; i++) {
				double affSum = 0;
				for(PHYNode n2 : nbrs) {
					n2.ancestorsWithCapacity = new PHYNode[numSamples];
					affSum += n2.getAAF(i);
				}
				if(affSum >= n.getAAF(i) + Parameters.AAF_ERROR_MARGIN) {
					// violation, try to find an ancestor with capacity
					if(n.ancestorsWithCapacity[i] == null) {
						return false;
					} else {
						
					}
					
				} else {
					for(PHYNode n2 : nbrs) {
						if (affSum < n.getAAF(i)) {
							// there is remaining capacity
							n2.ancestorsWithCapacity[i] = n;
						} else {
							// set the pointer to an ancestor with capacity
							n2.ancestorsWithCapacity[i] = n.ancestorsWithCapacity[i];
						}
					}
					
				}
			}
			
			if(t.treeEdges.get(n) != null) {
				nodes.addAll(t.treeEdges.get(n));
			}
		}
		
		return false;
	}
	
	/** Debugging only - tests that all the spanning trees found are different */
	public void testSpanningTrees() {
		for(int i = 0; i < spanningTrees.size(); i++) {
			for(int j = i + 1; j < spanningTrees.size(); j++) {
				Tree t1 = spanningTrees.get(i);
				Tree t2 = spanningTrees.get(j);
				
				// compare
				boolean sameEdges = true;
				for(PHYNode n1 : t1.treeEdges.keySet()) {
					for(PHYNode n2 : t1.treeEdges.get(n1)) {
						sameEdges &= t2.containsEdge(n1, n2);
					}
				}
				if(sameEdges) {
					System.out.println("Found same tree");
					System.out.println(t1);
					System.out.println(t2);
					return;
				}
			}
		}
		System.out.println("All trees are distinct");
	}
	
	
	/**
	 * Returns a string representation of the graph
	 */
	public String toString() {
		String graph = "--- PHYLOGENETIC CONSTRAINT GRAPH --- \n";
		graph += "numNodes = " + numNodes + ", ";
		graph += "numEdges = " + numEdges + "\n";
		
		// print nodes by level
		graph += "NODES: \n";
		for(int i = numSamples + 1; i >= 0; i--) {
			graph += "level = " + i + ": \n";
			ArrayList<PHYNode> levelNodes = nodes.get(i);
			if(levelNodes == null) {
				graph += "EMPTY \n";
			} else {
				for(PHYNode n : levelNodes) {
					graph += n.toString() + "\n";
				}
			}
		}
		graph += "EDGES: \n";
		for(PHYNode n1 : edges.keySet()) {
			ArrayList<PHYNode> nbrs = edges.get(n1);
			for(PHYNode n2 : nbrs) {
				graph += n1.getNodeId() + " -> " + n2.getNodeId() + "\n";
			}
		}
		
		return graph;
	}
	
	/**
	 * Node in the phylogenetic graph
	 * Represents a sub-population or sample (if leaf)
	 * Is associated with a given AAF (alternative allele frequency)
	 * 
	 */
	protected class PHYNode {
		
		/** Sub-population cluster that the node represents */
		private Cluster cluster;		
		
		/** SNP group the node belongs to */
		private SNPGroup snpGroup;
		
		/** Flag indicating if the node is a sample leaf*/
		private boolean isLeaf;
		
		/** Flag indicating if the node is the germline root */
		private boolean isRoot;
		
		/** The sample id if node is a leaf*/
		private int leafSampleId;
		
		/** Debugging-only node id */
		private int nodeId;
		
		/** Level in the constraint network */
		private int level;
		
		/** Pointer to an ancestor in the tree that has remaining AAF capacity */
		protected PHYNode[] ancestorsWithCapacity;
		
		/** 
		 * Internal node constructor
		 * @param g - SNP group the node belongs to
		 * @param nodeClusterId
		 */
		public PHYNode(SNPGroup g, int nodeClusterId, int networkLevel) {
			snpGroup = g;
			cluster = snpGroup.getSubPopulations()[nodeClusterId];
			isLeaf = false;
			nodeId = nodeCounter;
			nodeCounter++;
			level = networkLevel;
		}
		
		/**
		 * Leaf node constructor - represents each tumor sample
		 * @param sampleId - ID of the represented tumor sample
		 */
		public PHYNode(int sampleId) {
			isLeaf = true;
			leafSampleId = sampleId;
			nodeId = nodeCounter;
			nodeCounter++;
			level = 0;
		}
		
		/**
		 * Root node constructor
		 */
		public PHYNode() {
			isRoot = true;
			nodeId = nodeCounter;
			nodeCounter++;
			level = numSamples + 1;
		}
		
		/**
		 * Returns the SNP cluster that this node represents
		 */
		public Cluster getCluster() {
			return cluster;
		}
		
		/**
		 * Returns true if the node is a leaf
		 */
		public boolean isLeaf() {
			return isLeaf;
		}
		
		/**
		 * Returns the ID of the sample 
		 * @requires node is a leaf
		 */
		public int getLeafSampleId() {
			return leafSampleId;
		}
		
		public int getNodeId() {
			return nodeId;
		}
		
		public int getLevel() {
			return level;
		}
		
		public SNPGroup getSNPGroup() {
			return snpGroup;
		}
		
		/**
		 * Returns the cluster centroid AAF for the given sample ID
		 * Returns 0 if the sample is not represented
		 */
		public double getAAF(int sampleId) {
			if(isRoot) {
				return AAF_MAX;
			} 
			if(isLeaf) {
				return 0;
			}
			
			int sampleIndex = snpGroup.getSampleIndex(sampleId);
			if(sampleIndex == -1) {
				return 0;
			}
			return cluster.getCentroid()[sampleIndex];
		}
		
		/**
		 * Returns the cluster standard deviation for the given sample ID
		 * Returns 0 if the sample is not represented
		 */
		public double getStdDev(int sampleId) {
			if(isRoot) {
				return 0;
			} 
			if(isLeaf) {
				return 0;
			}
			
			int sampleIndex = snpGroup.getSampleIndex(sampleId);
			if(sampleIndex == -1) {
				return 0;
			}
			return cluster.getStdDev()[sampleIndex];
		}
		
		public String toString() {
			String node = "Node " + nodeId + ": ";
			if(!isLeaf && !isRoot) {
				node += "group tag = " + snpGroup.getTag() + ", ";
				node += cluster.toString();
			} else if(isLeaf) {
				node += "leaf sample id = " + leafSampleId;
			} else {
				node += "root";
			}
			return node;
		}
		
		public String getLabel() {
			String node = "";
			if(!isLeaf && !isRoot) {
				node += nodeId + ": \n";
				node += snpGroup.getTag() + "\n";
				node += "("+cluster.getMembership().size()+")";
				//node += cluster.toString();
			} else if(isLeaf) {
				node += "sample " + leafSampleId;
			} else {
				node += "root";
			}
			return node;
		}
		
		public boolean equals(Object o) {
			if(!(o instanceof PHYNode)) {
				return false;
			}
			PHYNode n = (PHYNode) o;
			if(this.nodeId == n.nodeId) {
				return true;
			} else {
				return false;
			}
		}
		
	}
	
	/**
	 * Edge in the phylogenetic graph
	 */
	protected class PHYEdge {
		PHYNode from;
		PHYNode to;
		
		public PHYEdge(PHYNode from, PHYNode to) {
			this.from = from;
			this.to = to;
		}
		
		public boolean equals(Object o) {
			if(!(o instanceof PHYEdge)) {
				return false;
			}
			PHYEdge e = (PHYEdge) o;
			if(this.from.equals(e.from) && this.to.equals(e.to)) {
				return true;
			} else {
				return false;
			}
		}
	}
	
	/**
	 * Spanning tree of the phylogenetic constraint network
	 */
	protected class Tree implements Comparable<Tree> {
		ArrayList<PHYNode> treeNodes;
		HashMap<PHYNode, ArrayList<PHYNode>> treeEdges;
		PHYNode[] parents;
		private double errorScore = -1;
		
		
		public Tree() {
			treeNodes = new ArrayList<PHYNode>();
			treeEdges = new HashMap<PHYNode, ArrayList<PHYNode>>();
			parents = new PHYNode[numNodes]; // excludes the root
		}
		
		public void addNode(PHYNode n) {
			if(!treeNodes.contains(n)) {
				treeNodes.add(n);
			}
		}
		
		public void addEdge(PHYNode from, PHYNode to) {
			ArrayList<PHYNode> nbrs = treeEdges.get(from);
			if(nbrs == null) {
				treeEdges.put(from, new ArrayList<PHYNode>());
			}
			if(!treeEdges.get(from).contains(to)) {
				treeEdges.get(from).add(to);
			}
			parents[to.getNodeId()] = from;
		}
		
		public void removeEdge(PHYNode from, PHYNode to) {
			ArrayList<PHYNode> nbrs = treeEdges.get(from);
			if(nbrs != null) {
				for(PHYNode n : nbrs) {
					if(n.equals(to)) {
						nbrs.remove(n);
						break;
					}
				}
			}
			
			// remove the node if no edge points to it
			boolean connected = false;
			for(PHYNode n : treeEdges.keySet()) {
				for(PHYNode n2 : treeEdges.get(n)) {
					if(to.equals(n2)) {
						connected = true;
						break;
					}
				}
			}
			if(!connected) {
				treeNodes.remove(to);
			}
			
			parents[to.getNodeId()] = null;
			
		}
		
		public boolean containsNode(PHYNode v) {
			for(PHYNode n : treeNodes) {
				if(n.equals(v)) {
					return true;
				}
			}
			return false;
		}
		
		public boolean containsEdge(PHYNode from, PHYNode to) {
			if(treeEdges.get(from) == null) return false;
			for(PHYNode n : treeEdges.get(from)) {
				if(n.equals(to)) {
					return true;
				}
			}
			return false;
		}
		
		/** 
		 * Returns a copy of the tree
		 */
		public Tree clone() {
			Tree copy = new Tree();
			copy.treeNodes.addAll(this.treeNodes);
			for(PHYNode n : this.treeEdges.keySet()) {
				ArrayList<PHYNode> nbrs = new ArrayList<PHYNode>();
				nbrs.addAll(this.treeEdges.get(n));
				copy.treeEdges.put(n, nbrs);
			}
			return copy;
		}
		
		/**
		 * Returns true if w is a descendant of v in this tree
		 */
		public boolean isDescendent(PHYNode v, PHYNode w) {
			ArrayList<PHYNode> nbrs = treeEdges.get(v);
			if(nbrs == null) {
				return false;
			}
			ArrayList<PHYNode> q = new ArrayList<PHYNode>(nbrs);
			while(q.size() > 0) {
				PHYNode n = q.remove(0);
				if(n.equals(w)) {
					return true;
				}
				if(treeEdges.get(n) != null) {
					q.addAll(treeEdges.get(n));
				}
			}
			return false;
		}
		
		public boolean checkConstraint(PHYNode n) {
			ArrayList<PHYNode> nbrs = treeEdges.get(n);			
			if(nbrs == null) return true;
			for(int i = 0; i < numSamples; i++) {
				double affSum = 0;
				for(PHYNode n2 : nbrs) {
					affSum += n2.getAAF(i);
				}
				if(affSum >= n.getAAF(i) + Parameters.AAF_ERROR_MARGIN) {
					return false;
				}
			}
			
			return true;
		}
		
		public String toString() {
			String graph = "--- SPANNING TREE --- \n";
			for(PHYNode n1 : treeEdges.keySet()) {
				ArrayList<PHYNode> nbrs = treeEdges.get(n1);
				for(PHYNode n2 : nbrs) {
					graph += n1.getNodeId() + " -> " + n2.getNodeId() + "\n";
				}
			}
			return graph;
		}
		
		/** Displays the tree */
		public void displayTree(String[] sampleNames) {			
			DirectedGraph<Integer, Integer> g = new DirectedSparseGraph<Integer, Integer>();
			HashMap<Integer, String> nodeLabels = new HashMap<Integer, String>();
				
			int edgeId = 0;
			for (PHYNode n : treeEdges.keySet()) {
				g.addVertex(n.getNodeId());
				nodeLabels.put(n.getNodeId(), n.getLabel());
				for(PHYNode n2 : treeEdges.get(n)) {
					if(!g.containsVertex(n2.getNodeId())) {
						g.addVertex(n2.getNodeId());
						nodeLabels.put(n2.getNodeId(), n2.getLabel());
					}
					g.addEdge(edgeId, n.getNodeId(), n2.getNodeId(), EdgeType.DIRECTED);
					edgeId++;
				}
			}
			
			// add sample leaves
			for(int i = 0; i < numSamples; i++) {
				PHYNode n = new PHYNode(i);
				g.addVertex(-n.getNodeId());
				nodeLabels.put(-n.getNodeId(), sampleNames[i]);
				
				// find a parent in the closest higher level		 
				boolean found = false;
				ArrayList<PHYNode> parents = new ArrayList<PHYNode>();
				ArrayList<PHYNode> sameLevelParents = new ArrayList<PHYNode>();
				for(int j = n.getLevel() + 1; j <= numSamples; j++) {
					ArrayList<PHYNode> fromLevelNodes = nodes.get(j);
					if(fromLevelNodes == null) continue;
					for(PHYNode n2 : fromLevelNodes) {
						if(n2.getAAF(i) > 0) {
							boolean addEdge = true;
							for(PHYNode p : parents) {
								if(this.isDescendent(n2, p)) {
									addEdge = false;
									break;
								}
							}
							if(addEdge) {
								sameLevelParents.add(n2);
								parents.add(n2);
								found = true;
							}
						}
					}
					
					// remove nodes that are in same level that are connected
					ArrayList<PHYNode> toRemove = new ArrayList<PHYNode>();
					for(PHYNode n1 : sameLevelParents) {
						for(PHYNode n2 : sameLevelParents) {
							if(this.isDescendent(n1, n2)) {
								toRemove.add(n1);
							}
						}
					}
					sameLevelParents.removeAll(toRemove);
					
					for(PHYNode n2 : sameLevelParents) {
						g.addEdge(edgeId, n2.getNodeId(), -n.getNodeId());
						edgeId++;
					}
					
					sameLevelParents.clear();
					
				}
				if(!found) {
					g.addEdge(edgeId, 0, -n.getNodeId());
					edgeId++;
				}
			}			
			new TreeVisualizer(g, nodeLabels);	
		}
		
		/** 
		 * Returns the error score associated with the tree, 
		 * which is the sqrt of the sum of the children AAF sum deviation from the parent AAF
		 */
		public double getErrorScore() {
			if(errorScore == -1) {
				computeErrorScore();
			}
			return errorScore;
		}
		
		public double computeErrorScore() {
			double err = 0;
			for(PHYNode n : treeEdges.keySet()) {
				ArrayList<PHYNode> nbrs = treeEdges.get(n);			
				for(int i = 0; i < numSamples; i++) {
					double affSum = 0;
					for(PHYNode n2 : nbrs) {
						affSum += n2.getAAF(i);
					}
					if(affSum > n.getAAF(i)) {
						err += Math.pow(affSum - n.getAAF(i), 2);
					}
				}
			}
			errorScore = Math.sqrt(err);
			return errorScore;
		}
		
		public int compareTo(Tree t) {
			return new Double(this.getErrorScore()).compareTo(t.getErrorScore());
		}
		
		/**
		 * Returns the sub-populations of a given sample
		 */
		public String getLineage(int sampleId, String sampleName) {
			StringBuilder lineage = new StringBuilder();
			String indent = "";
			lineage.append(sampleName + ":\n");
			lineage.append("GERMLINE\n");
			
			// traverse the tree starting from the root in DFS order
			for(PHYNode n : treeEdges.get(treeNodes.get(0))) {
				getLineageHelper(lineage, indent, n, sampleId);
			}
			return lineage.toString();
		}
		
		private void getLineageHelper(StringBuilder lineage, String indent, PHYNode n, int sampleId) {
			indent += "\t";			
			
			DecimalFormat df = new DecimalFormat("#.##");
			if(n.getSNPGroup().containsSample(sampleId)) {
				lineage.append(indent + n.getSNPGroup().getTag() + ": " + df.format(n.getAAF(sampleId)) + " [" + df.format(n.getStdDev(sampleId)) + "]\n");
			}
			if(treeEdges.get(n) != null) {
				for(PHYNode nbr : treeEdges.get(n)) {
					getLineageHelper(lineage, indent, nbr, sampleId);
				}
			}
		}
	}	
}
