package com.oracle.svm.hosted.image;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.hub.DynamicHubCompanion;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import org.graalvm.collections.Pair;

import com.oracle.svm.core.image.ImageHeapPartition;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.image.NativeImageHeap.ObjectInfo;
import com.oracle.svm.hosted.meta.HostedField;

public class NativeImageHeapGraph {
    private final NativeImageHeap heap;
    private final long totalHeapSizeInBytes;
    private List<ConnectedComponent> connectedComponents;
    private final AbstractGraph<ObjectInfo> objectInfoGraph;
    private final BigBang bb;

    private static AbstractGraph<ObjectInfo> getGraphInstance() {
        return new DirectedGraph<>();
    }

    public NativeImageHeapGraph(NativeImageHeap heap, long imageHeapSize) {
        System.out.println("Constructing Native Image Heap Graph: ... ");
        long start = System.currentTimeMillis();
        this.heap = heap;
        this.totalHeapSizeInBytes = imageHeapSize;
        this.objectInfoGraph = computeObjectInfoGraph(this.heap);
        this.connectedComponents = computeConnectedComponents(objectInfoGraph, this.heap);
        this.bb = this.heap.getMetaAccess().getUniverse().getBigBang();
        long end = System.currentTimeMillis();
        System.out.printf("Elapsed seconds: %.4f\n", (end - start) / 1000.0f);
    }

    private static AbstractGraph<ObjectInfo> computeObjectInfoGraph(NativeImageHeap heap) {
        AbstractGraph<ObjectInfo> graph = getGraphInstance();
        for (ObjectInfo objectInfo : heap.getObjects()) {
            connectChildToParentObjects(graph, objectInfo);
        }
        return graph;
    }

    private static void connectChildToParentObjects(AbstractGraph<ObjectInfo> graph, ObjectInfo childObjectInfo) {
        for (Object reason : childObjectInfo.getAllReasons()) {
            if (reason instanceof ObjectInfo && !internalObjectOrReachableFromInternalObject((ObjectInfo) reason))
                graph.connect((ObjectInfo) reason, childObjectInfo);
        }
    }

    private static List<ConnectedComponent> computeConnectedComponents(AbstractGraph<ObjectInfo> graph, NativeImageHeap heap) {
        ConnectedComponentsCollector connectedComponentsCollector = new ConnectedComponentsCollector(graph);
        for (ObjectInfo root : graph.getRoots()) {
            if (connectedComponentsCollector.isNotVisited(root)) {
                graph.dfs(root, connectedComponentsCollector);
            }
        }
       return connectedComponentsCollector.getConnectedComponents()
               .stream().map(objects -> new ConnectedComponent(objects, heap))
               .sorted(Comparator.comparing(ConnectedComponent::getSizeInBytes).reversed())
               .collect(Collectors.toList());
    }

    public void printEntryPointsReport(PrintWriter out) {
        Set<String> entryPoints = new TreeSet<>();
        for (ObjectInfo objectInfo : this.heap.getObjects()) {
            for (Object reason : objectInfo.getAllReasons()) {
                if (!(reason instanceof ObjectInfo)) {
                    entryPoints.add(formatReason(reason));
                }
            }
        }
        entryPoints.forEach(out::println);
    }

    public void printReferenceChainGraph(PrintWriter out) {
        DirectedGraph<ObjectInfo> graph = new DirectedGraph<>();
        for (ObjectInfo object : heap.getObjects().stream().filter(NativeImageHeapGraph::suppressInternalObjects).collect(Collectors.toList())) {
            List<ObjectInfo> referenceChain = object.upwardsReferenceChain();
            if (mainReasonFilter(referenceChain.get(referenceChain.size() - 1))) {
                ObjectInfo child = referenceChain.get(0);
                for (int i = 1; i < referenceChain.size(); ++i) {
                    ObjectInfo parent = referenceChain.get(i);
                    graph.connect(parent, child);
                    child = parent;
                }
            }
        }
        out.println("digraph {");
        graph.getLeaves().stream().map(o -> makeDownwardsReferenceChain(bb, o, null)).forEach(out::println);
        out.println("}");
    }

    public void printReferenceChainStringReport(PrintWriter out) {
        for (ObjectInfo info : this.heap.getObjects()) {
            out.println(makeReferenceChainString(bb, info, null));
        }
    }

    public void printConnectedComponentsHistogramsAndEntryPoints(PrintWriter out) {
        out.println("============Native Image Heap Object Graph Report============");
        out.printf("Total Heap Size: %.3fMB\n", MB(this.totalHeapSizeInBytes));
        out.printf("Total number of objects in the heap: %d\n", this.heap.getObjects().size());
        out.printf("Number of connected components in the report %d\n", this.connectedComponents.size());
        out.println();
        out.println("===========Connected components in the Native Image Heap===========");
        for (int i = 0; i < connectedComponents.size(); i++) {
            int componentId = i + 1;
            ConnectedComponent connectedComponent = connectedComponents.get(i);
            float sizeInMb = connectedComponent.getSizeInMB();
            float percentageOfTotalHeapSize = 100.0f * (float) connectedComponent.getSizeInBytes() /
                            this.totalHeapSizeInBytes;
            HeapHistogram objectHistogram = new HeapHistogram(out);
            connectedComponent.getObjects().forEach(o -> objectHistogram.add(o, o.getSize()));
            objectHistogram.printHeadings(
                            String.format("Component=%d | Size=%.4fMB | Percentage of total image heap size=%.4f%%\n", componentId, sizeInMb, percentageOfTotalHeapSize));
            objectHistogram.print();
//            connectedComponent.getObjects().get(0).getAllReasons().stream().map(NativeImageHeapGraph::formatReason).forEach(out::println);
            Set<String> uniqueReasons = new TreeSet<>();
            for (ObjectInfo object : connectedComponent.getObjects()) {
                for (Object reason : object.getAllReasons()) {
                    if (!(reason instanceof ObjectInfo)) {
                        String formatedReason = formatReason(reason);
                        uniqueReasons.add(formatedReason);
                    }
                }
            }
            uniqueReasons.forEach(out::println);
            out.println();
        }
    }

    private static final String[] suppressObjectsOfType = {
                    DynamicHub.class.toString(),
                    DynamicHubCompanion.class.toString(),
    };

    private static boolean suppressInternalObjects(ObjectInfo info) {
        String infoClazzName = info.getObject().getClass().toString();
        for (String clazzName : suppressObjectsOfType) {
            if (infoClazzName.contains(clazzName)) {
                return false;
            }
        }
        return true;
    }

    public static Object constantAsObject(BigBang bb, JavaConstant constant) {
        return bb.getSnippetReflectionProvider().asObject(Object.class, constant);
    }

    private static String escape(String str) {
        return str.replace("\n", "\\n").replace("\r", "\\r");
    }

    static String constantAsString(BigBang bb, JavaConstant constant) {
        Object object = constantAsObject(bb, constant);
        if (object instanceof String) {
            String str = (String) object;
            str = escape(str);
            return str;
        } else {
            return escape(JavaKind.Object.format(object));
        }
    }

    private static String selectReason(ObjectInfo info) {
        String[] patterns = NativeImageHeapGraphFeature.Options.NativeImageHeapGraphRootFilter.getValue().split(",");
        for (Object reason : info.getAllReasons()) {
            if (reason instanceof String && Arrays.stream(patterns).anyMatch(p -> reason.toString().contains(p))) {
                return (String) reason;
            }
        }
        return "NoReasonToSelectFor: " + info;
    }

    private static String makeDownwardsReferenceChain(BigBang bb, ObjectInfo info, Predicate<ObjectInfo> objectFilter) {
        List<ObjectInfo> chain = info.upwardsReferenceChain();
        Collections.reverse(chain);
        StringBuilder result = new StringBuilder();
        result.append("\"");
        result.append(selectReason(chain.get(0)));
        result.append("\" -> ");
        for (int i = 0; i < chain.size(); i++) {
            ObjectInfo cur = chain.get(i);
            if (objectFilter == null || objectFilter.test(cur)) {
                result.append("\"");
                result.append(cur.getObject().getClass().getName())
                                .append("{")
                                .append(constantAsString(bb, cur.getConstant()).replace("\"", "\\\""))
                                .append("}");
                result.append("\"");
                if (i < chain.size() - 1)
                    result.append(" -> ");
            }
        }
        result.append(";");
        return result.toString();
    }

    private String makeReferenceChainString(BigBang bb, ObjectInfo info, Predicate<ObjectInfo> objectFilter) {
        // TODO(mspasic): read value as java constant here and try to output it
        StringBuilder result = new StringBuilder();
        Object cur = info;
        ObjectInfo prev = null;
        while (cur instanceof ObjectInfo) {
            ObjectInfo curObjectInfo = (ObjectInfo) cur;
            if (objectFilter == null || objectFilter.test(curObjectInfo)) {
                result.append(curObjectInfo.getObject().getClass().getName())
                                .append("{");
                if (constantAsObject(bb, curObjectInfo.getConstant()) instanceof String) {
                    result.append(constantAsString(bb, curObjectInfo.getConstant()));
                }
                result.append("} -> ");
            }
            prev = curObjectInfo;
            cur = curObjectInfo.getAllReasons();
        }
        result.append("::");
        for (Object reason : prev.getAllReasons()) {
            result.append("=");
            result.append(formatReason(reason));
            result.append("=");
        }
        return result.toString();
    }

    private static boolean objectTypeFilter(ObjectInfo o) {
        String[] filters = NativeImageHeapGraphFeature.Options.ImageHeapObjectTypeFilter.getValue().split(",");
        for (String filter : filters) {
            if (o.getObject().getClass().getName().contains(filter)) {
                return true;
            }
        }
        return false;
    }


    private final static class ListCollector<Node> implements AbstractGraph.NodeVisitor<Node> {
        private final List<Node> nodes = new ArrayList<>();

        @Override
        public void accept(AbstractGraph<Node> graph, AbstractGraph.VisitorState<Node> state) {
            nodes.add(state.currentNode);
        }

        public List<Node> getNodes() {
            return nodes;
        }
    }

    public final static class CollectNLevels<Node> implements AbstractGraph.NodeVisitor<Node> {
        private final ArrayList<Node> levels = new ArrayList<>();
        private final int firstNLevels;
        private boolean shouldTerminate = false;

        public CollectNLevels(int firstNLevels) {
            this.firstNLevels = firstNLevels;
        }

        ArrayList<Node> getNodes() {
            return levels;
        }

        @Override
        public void accept(AbstractGraph<Node> graph, DirectedGraph.VisitorState<Node> state) {
            if (state.level == firstNLevels) {
                shouldTerminate = true;
                return;
            }
            levels.add(state.currentNode);
        }

        @Override
        public boolean shouldTerminateVisit() {
            return shouldTerminate;
        }
    }

    private static final class DirectedGraphCollector implements AbstractGraph.NodeVisitor<ObjectInfo> {
        public final DirectedGraph<ObjectInfo> subgraph = new DirectedGraph<>();

        @Override
        public void accept(AbstractGraph<ObjectInfo> graph, DirectedGraph.VisitorState<ObjectInfo> state) {
            subgraph.connect(state.parentNode, state.currentNode);
        }
    }

    private static final class ConnectedComponentsCollector implements AbstractGraph.NodeVisitor<ObjectInfo> {
        private final AbstractGraph<ObjectInfo> graph;
        private final List<List<ObjectInfo>> connectedComponents = new ArrayList<>();
        private boolean[] visited;
        private int componentId = 0;
        private PrintWriter out;

        public ConnectedComponentsCollector(AbstractGraph<ObjectInfo> graph) {
            this.visited = new boolean[graph.getNumberOfNodes()];
            this.graph = graph;
            this.out = out;
        }

        @Override
        public void onStart(AbstractGraph<ObjectInfo> graph) {
            connectedComponents.add(new ArrayList<>());
        }

        @Override
        public void accept(AbstractGraph<ObjectInfo> graph, AbstractGraph.VisitorState<ObjectInfo> state) {
            int nodeId = graph.getNodeId(state.currentNode);
//            out.printf("%s -> %s\n", state.parentNode == null ? "null" : state.parentNode, state.currentNode);
            this.visited[nodeId] = true;
            connectedComponents.get(componentId).add(state.currentNode);
        }

        @Override
        public void onEnd(AbstractGraph<ObjectInfo> graph) {
            ++componentId;
        }

        @Override
        public boolean shouldVisit(ObjectInfo objectInfo) {
//            if (suppressInternalObjects(objectInfo)) {
//                return false;
//            }
            return !this.visited[graph.getNodeId(objectInfo)];
        }

        public int getNumOfConnectedComponents() {
            return componentId;
        }

        public boolean isNotVisited(ObjectInfo info) {
            int id = graph.getNodeId(info);
            if (id == -1) {
                return false;
            }
            return !this.visited[id];
        }

        public List<List<ObjectInfo>> getConnectedComponents() {
            return connectedComponents;
        }
    }

    private static Class<?> objectTypesToSuppress[] = {
        DynamicHub.class,
        DynamicHubCompanion.class
    };

    private static boolean isInternedStrings(ObjectInfo info) {
        for (Object reason : info.getAllReasons()) {
            if (reason instanceof String) {
                return ((String)reason).equals("internedStrings");
            }
        }
        return false;
    }

    private static boolean internalObjectOrReachableFromInternalObject(ObjectInfo objectInfo) {
        for (Class<?> clazz : objectTypesToSuppress) {
            if (objectInfo.getObject().getClass().equals(clazz)) {
                return true;
            }
        }

        if (isInternedStrings(objectInfo)) {
            return true;
        }
        return false;
    }

    public void printObjectsReport(PrintWriter out) {
        AbstractGraph<ObjectInfo> g = new UndirectedGraph<>();
        for (ObjectInfo objectInfo : this.heap.getObjects()) {
            if (!internalObjectOrReachableFromInternalObject(objectInfo))
                connectChildToParentObjects(g, objectInfo);
        }
        ConnectedComponentsCollector collector = new ConnectedComponentsCollector(g);
        for (ObjectInfo object : this.heap.getObjects()) {
            if (g.inGraph(object) && collector.isNotVisited(object))
                g.dfs(object, collector);
        }
        connectedComponents = collector.getConnectedComponents()
                .stream().map(objects -> new ConnectedComponent(objects, heap))
                .sorted(Comparator.comparing(ConnectedComponent::getSizeInBytes).reversed())
                .collect(Collectors.toList());
        printConnectedComponentsHistogramsAndEntryPoints(out);
    }

    public void printComponentsImagePartitionHistogramReport(PrintWriter out) {
        out.println("==========ImagePartitionStatistics per component=============");
        out.println("CSp - Component size in Partition");
        out.println("PS - Partition size");
        out.println("F - Total space taken by component inside a partition");
        for (ConnectedComponent connectedComponentInfo : connectedComponents) {
            out.printf("\n=========Object: %s - | %fMB | IdentityHashCode: %d=========\n",
                            connectedComponentInfo.getRoot().getClass(),
                            connectedComponentInfo.getSizeInMB(),
                            connectedComponentInfo.getRoot().getIdentityHashCode());
            out.printf("%-20s %-26s\n", "Partition", "CSp/PS=F");
            for (Pair<ImageHeapPartition, Long> partitionInfo : connectedComponentInfo.getHistogram()) {
                ImageHeapPartition partition = partitionInfo.getLeft();
                long componentSizeInPartition = partitionInfo.getRight();
                out.printf("%-20s %.4fMB/%.4fMB=%.4f\n",
                                partition,
                                MB(componentSizeInPartition),
                                MB(partition.getSize()),
                                (double) componentSizeInPartition / partition.getSize());
            }
        }
    }

    private static boolean mainReasonFilter(ObjectInfo info) {
        String[] patterns = NativeImageHeapGraphFeature.Options.NativeImageHeapGraphRootFilter.getValue().split(",");
        // Object reason = info.getMainReason();
        for (Object reason : info.getAllReasons()) {
            boolean result = false;
            if (reason instanceof String) {
                String r = (String) reason;
                result = Arrays.stream(patterns).anyMatch(r::contains);
            } else if (reason instanceof HostedField) {
                HostedField r = (HostedField) reason;
                result = Arrays.stream(patterns).anyMatch(p -> r.getDeclaringClass().getName().contains(p));
            }
            if (result) {
                return true;
            }
        }
        return false;
    }

    private static String formatReasonForDotFile(Object reason) {
        if (reason instanceof String) {
            return String.format("\"%s\"", reason);
        } else if (reason instanceof ObjectInfo) {
            ObjectInfo r = (ObjectInfo) reason;
            return String.format("%d", r.getIdentityHashCode());
        } else if (reason instanceof HostedField) {
            HostedField r = (HostedField) reason;
            return String.format("\"%s\"", r.getDeclaringClass().getName());
        } else {
            VMError.shouldNotReachHere("Unhandled type");
            return "Unhandled type in: NativeImageHeapGraph.formatReason([root]):179";
        }
    }

    private String formatReason(Object reason) {
        if (reason instanceof String) {
            return String.format("Method: %s", reason);
        } else if (reason instanceof ObjectInfo) {
            ObjectInfo r = (ObjectInfo) reason;
            return String.format("ObjectInfo: %s | %d | %s", r.getObjectClass().getName(), r.getIdentityHashCode(), constantAsString(bb, r.getConstant()));
        } else if (reason instanceof HostedField) {
            HostedField r = (HostedField) reason;
            return r.format("HostedField: class %H { static %t %n; }");
        } else {
            VMError.shouldNotReachHere("Unhandled type");
            return "Unhandled type in: NativeImageHeapGraph.formatReason([root]):179";
        }
    }

    private static float MB(long bytes) {
        return bytes / (1048576f);
    }

    private final static class ConnectedComponent {
        private final List<ObjectInfo> objects;
        private final long size;
        private final List<ImageHeapPartition> partitions;
        private final long[] componentSizeInPartition;
        private final Set<Object> reasons;

        public ConnectedComponent(List<ObjectInfo> objects, NativeImageHeap heap) {
            this.objects = objects;
            this.size = computeComponentSize(objects);
            this.partitions = Arrays.asList(heap.getLayouter().getPartitions());
            this.componentSizeInPartition = new long[partitions.size()];
            this.reasons = Collections.newSetFromMap(new IdentityHashMap<>());
            for (ObjectInfo object : objects) {
                ImageHeapPartition partition = object.getPartition();
                int index = this.partitions.indexOf(partition);
                componentSizeInPartition[index] += object.getSize();
            }
        }

        public Set<Object> getReasons() {
            return reasons;
        }

        private static long computeComponentSize(List<ObjectInfo> objects) {
            long totalSize = 0L;
            for (ObjectInfo o : objects) {
                totalSize += o.getSize();
            }
            return totalSize;
        }

        public long getSizeInBytes() {
            return size;
        }

        public float getSizeInMB() {
            return MB(size);
        }

        public List<ObjectInfo> getObjects() {
            return objects;
        }

        public ObjectInfo getRoot() {
            return objects.get(0);
        }

        public boolean shouldReportThisComponent() {
            List<String> rootFilterPatterns = Arrays.stream(
                            NativeImageHeapGraphFeature.Options.NativeImageHeapGraphRootFilter.getValue().split(","))
                            .map(String::strip)
                            .collect(Collectors.toList());

            for (String pattern : rootFilterPatterns) {
                boolean shouldReport = getRoot().toString().contains(pattern);
                if (shouldReport) {
                    return true;
                }
            }
            return false;
        }

        public List<Pair<ImageHeapPartition, Long>> getHistogram() {
            return IntStream.range(0, partitions.size())
                            .mapToObj(i -> Pair.create(partitions.get(i), componentSizeInPartition[i]))
                            .collect(Collectors.toList());
        }
    }
}