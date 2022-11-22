package com.oracle.svm.hosted.image;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.svm.hosted.Utils;
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
    private final List<ConnectedComponent> connectedComponents;
    private final BigBang bb;
    private final EnumMap<NativeImageHeap.ObjectGroup, Set<ObjectInfo>> groups;
    private static AbstractGraph<ObjectInfo> getGraphInstance() {
        return new UndirectedGraph<>();
    }

    public NativeImageHeapGraph(NativeImageHeap heap, BigBang bigBang, AbstractImage image) {
        System.out.println("\nConstructing Native Image Heap Graph: ... ");
        long start = System.currentTimeMillis();
        this.heap = heap;
        this.heap.getObjects().forEach(o -> o.preprocessInternedStringObjects(this.heap));
        this.heap.getObjects().forEach(o -> o.preprocessInternedStringBytes(this.heap));
        this.totalHeapSizeInBytes = image.getImageHeapSize();
        this.bb = bigBang;
        this.groups = new EnumMap<>(NativeImageHeap.ObjectGroup.class);
        this.connectedComponents = computeConnectedComponents(this.heap);
        long end = System.currentTimeMillis();

        System.out.printf("Computed in: %.4fs\n", (end - start) / 1000.0f);
    }

    private static <T> Set<T> getHashSetInstance() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static boolean shouldIncludeObjectInTheReport(ObjectInfo objectInfo) {
        if (objectInfo.getMainReason().toString().equals("Filler object")) {
            return false;
        }
        return true;
    }

    private List<ConnectedComponent> computeConnectedComponents(NativeImageHeap heap) {
        Set<ObjectInfo> allImageHeapObjects = getHashSetInstance();
        allImageHeapObjects.addAll(
                heap.getObjects().stream()
                .filter(NativeImageHeapGraph::shouldIncludeObjectInTheReport)
                .collect(Collectors.toList()));


        NativeImageHeap.ObjectGroup[] objectGroups = {
                        NativeImageHeap.ObjectGroup.BelongsToInternedStringsTable,
                        NativeImageHeap.ObjectGroup.BelongsToDynamicHub,
                        NativeImageHeap.ObjectGroup.BelongsToImageCodeInfo,
                        NativeImageHeap.ObjectGroup.BelongsToMethod,
                        NativeImageHeap.ObjectGroup.HostedField
        };

        List<ConnectedComponent> connectedComponents = new ArrayList<>();
        for (NativeImageHeap.ObjectGroup objectGroup : objectGroups) {
            Set<ObjectInfo> objects = removeObjectsBy(objectGroup, allImageHeapObjects);
            groups.put(objectGroup, objects);
            if (objectGroup != NativeImageHeap.ObjectGroup.BelongsToImageCodeInfo
                    && objectGroup != NativeImageHeap.ObjectGroup.BelongsToDynamicHub
                    && objectGroup != NativeImageHeap.ObjectGroup.BelongsToInternedStringsTable) {
                AbstractGraph<ObjectInfo> graph = constructGraph(objects);
                connectedComponents.addAll(computeConnectedComponentsInGraph(graph, objectGroup));
            }
        }
        return connectedComponents.stream()
                        .sorted(Comparator.comparing(ConnectedComponent::getSizeInBytes).reversed())
                        .collect(Collectors.toList());
    }

    private List<ConnectedComponent> computeConnectedComponentsInGraph(AbstractGraph<ObjectInfo> graph, NativeImageHeap.ObjectGroup objectGroup) {
        ConnectedComponentsCollector collector = new ConnectedComponentsCollector(graph);
        for (ObjectInfo node : graph.getRoots()) {
            if (collector.isNotVisited(node)) {
                graph.dfs(node, collector);
            }
        }
        return collector.getListOfObjectsForEachComponent()
                        .stream()
                        .map(objectsForComponent -> new ConnectedComponent(objectsForComponent, this.heap))
                        .collect(Collectors.toList());
    }

    private static Set<ObjectInfo> removeObjectsBy(NativeImageHeap.ObjectGroup reason, Set<ObjectInfo> objects) {
        Set<ObjectInfo> result = getHashSetInstance();
        for (Iterator<ObjectInfo> iterator = objects.iterator(); iterator.hasNext();) {
            ObjectInfo o = iterator.next();
            if (o.belongsTo(reason)) {
                result.add(o);
                iterator.remove();
            }
        }
        return result;
    }

    private AbstractGraph<ObjectInfo> constructGraph(Set<ObjectInfo> objects) {
        AbstractGraph<ObjectInfo> graph = getGraphInstance();
        for (ObjectInfo objectInfo : objects) {
            graph.addNode(objectInfo);
            for (Object referencesToThisObject : objectInfo.getAllReasons()) {
                if (referencesToThisObject instanceof ObjectInfo && objects.contains(referencesToThisObject)) {
                    graph.connect((ObjectInfo) referencesToThisObject, objectInfo);
                }
            }
        }
        return graph;
    }

    private static long computeTotalSize(Collection<ObjectInfo> objects) {
        long sum = 0;
        for (ObjectInfo object : objects) {
            sum += object.getSize();
        }
        return sum;
    }

    public void printEntryPointsReport(PrintWriter out) {
        TreeSet<String> entryPoints = new TreeSet<>();
        for (int i = 0, connectedComponentsSize = connectedComponents.size(); i < connectedComponentsSize; i++) {
            ConnectedComponent connectedComponent = connectedComponents.get(i);
            for (ObjectInfo objectInfo : connectedComponent.getObjects()) {
                if (!(objectInfo.getMainReason() instanceof ObjectInfo)) {
                    entryPoints.add("EntryPoint: " + formatReason(objectInfo.getMainReason()));
                } else {
                    for (Object reason : objectInfo.getAllReasons()) {
                        if (!(reason instanceof ObjectInfo)) {
                            entryPoints.add("OtherReason: " + formatReason(reason));
                        }
                    }
                }
            }
            for (String entryPoint : entryPoints) {
                out.printf("ComponentId=%d=%s\n", i, entryPoint);
            }
            entryPoints.clear();
        }
    }

    public void printAllImageHeapObjects(PrintWriter out) {
        out.println("ObjectInfo(objects class, objects identity hash code, constant value, category");
        for (ObjectInfo info : this.heap.getObjects()) {
            out.println(formatObject(info, bb));
        }
    }

    public void printObjectsForEachComponent(PrintWriter out) {
        out.println("ConnectedComponentId=ObjectInfo(objects class, objects identity hash code, constant value, category");
        for (int i = 0; i < connectedComponents.size(); i++) {
            ConnectedComponent connectedComponent = connectedComponents.get(i);
            for (ObjectInfo info : connectedComponent.getObjects()) {
                out.printf("ComponentId=%d=%s\n", i, formatObject(info, bb));
            }
        }
    }

    public void printObjectsAndReferencesForEachComponent(PrintWriter out) {
        out.println("ConnectedComponentId=ObjectInfo(objects class");
        for (int i = 0; i < connectedComponents.size(); i++) {
            ConnectedComponent connectedComponent = connectedComponents.get(i);
            for (ObjectInfo objectInfo : connectedComponent.getObjects()) {
                out.printf("ComponentId=%d=%s <- ", i, formatObject(objectInfo, bb));
                for (Object reason : objectInfo.getAllReasons()) {
                    out.printf("%d=%s; ", i, formatObject(reason, bb));
                }
                out.println();
            }
            out.println();
        }
    }

    private static final int HEADING_WIDTH = 140;
    private static String fillHeading(String title) {
        String fill = "=".repeat(Math.max(HEADING_WIDTH - title.length(), 8)/2);
        return String.format("%s %s %s%s", fill, title, fill, title.length()%2 == 0 ? "" : "=");
    }

    public void printConnectedComponentsHistogramsAndEntryPoints(PrintWriter out) {
        String title = "Native image heap connected components report";

        out.println(fillHeading(title));
        out.printf("Total Heap Size: %s\n", Utils.bytesToHuman(totalHeapSizeInBytes));
        long imageCodeInfoSizeInBytes = computeTotalSize(groups.get(NativeImageHeap.ObjectGroup.BelongsToImageCodeInfo)) ;
        long dynamicHubsSizeInBytes = computeTotalSize(groups.get(NativeImageHeap.ObjectGroup.BelongsToDynamicHub));
        long internedStringsSizeInBytes = computeTotalSize(groups.get(NativeImageHeap.ObjectGroup.BelongsToInternedStringsTable));
        long theRest = totalHeapSizeInBytes - dynamicHubsSizeInBytes - internedStringsSizeInBytes - imageCodeInfoSizeInBytes;
        out.printf("\tImage code info size: %s\n", Utils.bytesToHuman(imageCodeInfoSizeInBytes));
        out.printf("\tDynamic hubs size: %s\n", Utils.bytesToHuman(dynamicHubsSizeInBytes));
        out.printf("\tInterned strings size: %s\n", Utils.bytesToHuman(internedStringsSizeInBytes));
        out.printf("\tIn connected components report: %s\n", Utils.bytesToHuman(theRest)) ;
        out.printf("Total number of objects in the heap: %d\n", this.heap.getObjects().size());
        out.printf("Number of connected components in the report %d", this.connectedComponents.size());
        for (int i = 0; i < connectedComponents.size(); i++) {
            ConnectedComponent connectedComponent = connectedComponents.get(i);
            if (connectedComponent.getObjects().get(0).belongsTo(NativeImageHeap.ObjectGroup.BelongsToImageCodeInfo)) {
                continue;
            }
            float percentageOfTotalHeapSize = 100.0f * (float) connectedComponent.getSizeInBytes() /
                            this.totalHeapSizeInBytes;
            HeapHistogram objectHistogram = new HeapHistogram(out);
            connectedComponent.getObjects().forEach(o -> objectHistogram.add(o, o.getSize()));
            String headingInfo =  String.format("ComponentId=%d | Size=%s | Percentage of total image heap size=%.4f%%", i,
                    Utils.bytesToHuman(connectedComponent.getSizeInBytes()),
                    percentageOfTotalHeapSize);

            out.println();
            String fullHeading = fillHeading(headingInfo);
            objectHistogram.printHeadings(String.format("%s\n%s", "=".repeat(fullHeading.length()), fullHeading));
            objectHistogram.print();

            Collection<ObjectInfo> roots = connectedComponent.getObjects();
            Set<String> methods = getMethodAccess(roots);
            Set<HostedField> staticFields = getHostedFieldsAccess(roots);

            int entryPointLimit = 10;
            if (!staticFields.isEmpty()) {
                out.printf("\nComponent %d static field accesses:\n", i);
                // TODO(mspasic): static fields format
                for (HostedField field : staticFields.stream().limit(entryPointLimit).collect(Collectors.toList())) {
                    out.printf("\t%s\n", field.format("%H#%n"));
                }
                if (staticFields.size() > entryPointLimit) {
                    out.printf("\t... %d more in the entry points report\n", staticFields.size() - entryPointLimit);
                }
            }
            if (!methods.isEmpty()) {
                // TODO(mspasic):  method acccess points
                out.printf("\nMethods accessing connected component %d:\n", i);
                for (String methodName : methods.stream().limit(entryPointLimit).collect(Collectors.toList())) {
                    out.printf("\t%s\n", formatMethodAsLink(methodName));
                }
                if (methods.size() > entryPointLimit) {
                    out.printf("\t... %d more in the entry points report\n", methods.size() - entryPointLimit);
                }
            }
        }
    }

    public void dumpConnectedComponentSizes(PrintWriter out) {
        out.println("{");
        out.printf("\"ImageCodeInfoSize\":%d,\n", computeTotalSize(groups.get(NativeImageHeap.ObjectGroup.BelongsToImageCodeInfo)));
        out.printf("\"DynamicHubsSize\":%d,\n", computeTotalSize(groups.get(NativeImageHeap.ObjectGroup.BelongsToDynamicHub)));
        out.printf("\"InternedStrings\":%d,\n", computeTotalSize(groups.get(NativeImageHeap.ObjectGroup.BelongsToInternedStringsTable)));
        out.printf("\"Components\":[");
        for (ConnectedComponent connectedComponent : connectedComponents) {
            out.print(connectedComponent.getSizeInBytes());
            out.print(",");
        }
        out.println("]");
        out.println("}");
    }

    private static Set<String> getMethodAccess(Collection<ObjectInfo> objects) {
        Set<String> methods = new TreeSet<>();
        for (ObjectInfo object : objects) {
            if (object.getMainReason() instanceof String) {
                methods.add((String) object.getMainReason());
            } else {
                for (Object reason : object.getAllReasons()) {
                    if (reason instanceof String) { // TODO(mspasic): eliminate dataSection, staticFields...
                        methods.add((String) reason);
                    }
                }
            }
        }
        return methods;
    }
    private static String formatMethodAsLink(String method) {
        int lastDot = method.lastIndexOf(".");
        if (lastDot != -1) {
            return method.substring(0, lastDot) + '#' + method.substring(lastDot + 1);
        } else {
            return method;
        }
    }
    private static Set<HostedField> getHostedFieldsAccess(Collection<ObjectInfo> objects) {
        Set<HostedField> hostedFields = getHashSetInstance();
        for (ObjectInfo object : objects) {
            for (Object reason : object.getAllReasons()) {
                if (reason instanceof HostedField) {
                    hostedFields.add((HostedField) reason);
                }
            }
        }
        return hostedFields;
    }

    public void printImagePartitionsReport(PrintWriter out) {
        for (int i = 0; i < connectedComponents.size(); i++) {
            ConnectedComponent connectedComponent = connectedComponents.get(i);
            List<Pair<ImageHeapPartition, Long>> imageHeapPartitionDistribution = connectedComponent.getImageHeapPartitionDistribution();
            float percentageOfTotalHeapSize = 100.0f * (float) connectedComponent.getSizeInBytes() / this.totalHeapSizeInBytes;
            out.printf("ComponentId=%d | Size=%s | Percentage of total image heap size=%f%%\n",
                    i,
                    Utils.bytesToHuman(connectedComponent.getSizeInBytes()),
                    percentageOfTotalHeapSize);

            out.printf("%-20s %-20s %s\n", "Partition", "Taken space", "Percentage of total heap size");
            for (Pair<ImageHeapPartition, Long> partition : imageHeapPartitionDistribution) {
                long partitionSize = partition.getLeft().getSize();
                long takenSpace = partition.getRight();
                float percentage = 100.0f * takenSpace / partitionSize;
                out.printf("%-20s %-20s %f%%\n", partition.getLeft().getName(), String.format("%s/%s", Utils.bytesToHuman(takenSpace), Utils.bytesToHuman(partitionSize)), percentage);
            }
            out.println();
        }
    }

    private static String formatReason(Object reason) {
        if (reason instanceof String) {
            return String.format("Method(%s)", reason);
        } else if (reason instanceof ObjectInfo) {
            ObjectInfo r = (ObjectInfo) reason;
            return String.format("ObjectInfo(class %s, %d, %s)", r.getObjectClass().getName(), r.getIdentityHashCode(), r.getPulledInBySetAsString());
        } else if (reason instanceof HostedField) {
            HostedField r = (HostedField) reason;
            return r.format("HostedField(class %H { static %t %n; })");
        } else {
            VMError.shouldNotReachHere("Unhandled type");
            return null;
        }
    }

    private String formatObject(Object reason, BigBang bb) {
        if (reason instanceof String) {
            return String.format("Method(%s)", reason);
        } else if (reason instanceof ObjectInfo) {
            ObjectInfo r = (ObjectInfo) reason;
            return String.format("ObjectInfo(class %s, %d, %s, %s)", r.getObjectClass().getName(), r.getIdentityHashCode(), constantAsString(bb, r.getConstant()), r.getPulledInBySetAsString());
        } else if (reason instanceof HostedField) {
            HostedField r = (HostedField) reason;
            return r.format("HostedField(class %H { static %t %n; })");
        } else {
            VMError.shouldNotReachHere("Unhandled type");
            return null;
        }
    }

    private static Object constantAsObject(BigBang bb, JavaConstant constant) {
        return bb.getSnippetReflectionProvider().asObject(Object.class, constant);
    }

    private static String escape(String str) {
        return str.replace("\n", "\\n").replace("\r", "\\r").replace("\"", "\"\"");
    }

    private static String constantAsString(BigBang bb, JavaConstant constant) {
        Object object = constantAsObject(bb, constant);
        if (object instanceof String) {
            String str = (String) object;
            str = "\"" + escape(str) + "\"";
            return str;
        } else {
            return escape(JavaKind.Object.format(object));
        }
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

    private final static class CollectNLevels<Node> implements AbstractGraph.NodeVisitor<Node> {
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

        public ConnectedComponentsCollector(AbstractGraph<ObjectInfo> graph) {
            this.visited = new boolean[graph.getNumberOfNodes()];
            this.graph = graph;
        }

        @Override
        public void onStart(AbstractGraph<ObjectInfo> graph) {
            connectedComponents.add(new ArrayList<>());
        }

        @Override
        public void accept(AbstractGraph<ObjectInfo> graph, AbstractGraph.VisitorState<ObjectInfo> state) {
            int nodeId = graph.getNodeId(state.currentNode);
            this.visited[nodeId] = true;
            connectedComponents.get(componentId).add(state.currentNode);
        }

        @Override
        public void onEnd(AbstractGraph<ObjectInfo> graph) {
            ++componentId;
        }

        @Override
        public boolean shouldVisit(ObjectInfo objectInfo) {
            return !this.visited[graph.getNodeId(objectInfo)];
        }

        public boolean isNotVisited(ObjectInfo info) {
            int id = graph.getNodeId(info);
            if (id == -1) {
                return false;
            }
            return !this.visited[id];
        }

        public List<List<ObjectInfo>> getListOfObjectsForEachComponent() {
            return connectedComponents;
        }
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
                if (index >= 0) // filler objects might not be added to any partition
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

        public List<ObjectInfo> getObjects() {
            return objects;
        }

        public Set<ObjectInfo> getRoots() {
            Set<ObjectInfo> roots = getHashSetInstance();
            for (ObjectInfo object : objects) {
                if (!referencedByOtherObject(object)) {
                   roots.add(object);
                }
            }
            return roots;
        }

        private static boolean referencedByOtherObject(ObjectInfo info) {
            return !info.isRootObject();
        }

        public List<Pair<ImageHeapPartition, Long>> getImageHeapPartitionDistribution() {
            return IntStream.range(0, partitions.size())
                            .mapToObj(i -> Pair.create(partitions.get(i), componentSizeInPartition[i]))
                            .collect(Collectors.toList());
        }
    }
}