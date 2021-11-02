/*
 * Copyright (c) 2016, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.analysis;

import static com.oracle.graal.pointsto.reports.AnalysisReportsOptions.PrintAnalysisCallTree;
import static com.oracle.svm.hosted.NativeImageOptions.MaxReachableTypes;

import java.util.concurrent.ForkJoinPool;
import java.util.regex.Pattern;

import org.graalvm.compiler.core.common.SuppressSVMWarnings;
import org.graalvm.compiler.graph.NodeSourcePosition;
import org.graalvm.compiler.options.OptionValues;

import com.oracle.graal.pointsto.ObjectScanner;
import com.oracle.graal.pointsto.ObjectScanner.ScanReason;
import com.oracle.graal.pointsto.PointsToAnalysis;
import com.oracle.graal.pointsto.constraints.UnsupportedFeatures;
import com.oracle.graal.pointsto.flow.MethodTypeFlow;
import com.oracle.graal.pointsto.flow.MethodTypeFlowBuilder;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.meta.AnalysisUniverse;
import com.oracle.graal.pointsto.meta.HostedProviders;
import com.oracle.graal.pointsto.reports.CallTreePrinter;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.graal.meta.SubstrateReplacements;
import com.oracle.svm.core.meta.SubstrateObjectConstant;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.hosted.HostedConfiguration;
import com.oracle.svm.hosted.SVMHost;
import com.oracle.svm.hosted.meta.HostedType;
import com.oracle.svm.hosted.substitute.AnnotationSubstitutionProcessor;

import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.ResolvedJavaType;

public class NativeImagePointsToAnalysis extends PointsToAnalysis implements Inflation {

    private final Pattern illegalCalleesPattern;
    private final Pattern targetCallersPattern;
    private final AnnotationSubstitutionProcessor annotationSubstitutionProcessor;
    private final DynamicHubInitializer dynamicHubInitializer;
    private final UnknownFieldHandler unknownFieldHandler;

    public NativeImagePointsToAnalysis(OptionValues options, AnalysisUniverse universe, HostedProviders providers, AnnotationSubstitutionProcessor annotationSubstitutionProcessor,
                    ForkJoinPool executor, Runnable heartbeatCallback, UnsupportedFeatures unsupportedFeatures) {
        super(options, universe, providers, universe.hostVM(), executor, heartbeatCallback, new SubstrateUnsupportedFeatures(), SubstrateOptions.parseOnce());
        this.annotationSubstitutionProcessor = annotationSubstitutionProcessor;

        String[] targetCallers = new String[]{"com\\.oracle\\.graal\\.", "org\\.graalvm[^\\.polyglot\\.nativeapi]"};
        targetCallersPattern = buildPrefixMatchPattern(targetCallers);

        String[] illegalCallees = new String[]{"java\\.util\\.stream", "java\\.util\\.Collection\\.stream", "java\\.util\\.Arrays\\.stream"};
        illegalCalleesPattern = buildPrefixMatchPattern(illegalCallees);

        dynamicHubInitializer = new DynamicHubInitializer(universe, metaAccess, unsupportedFeatures, providers.getConstantReflection());
        unknownFieldHandler = new UnknownFieldHandler(metaAccess);
    }

    @Override
    public MethodTypeFlowBuilder createMethodTypeFlowBuilder(PointsToAnalysis bb, MethodTypeFlow methodFlow) {
        return HostedConfiguration.instance().createMethodTypeFlowBuilder(bb, methodFlow);
    }

    @Override
    protected void checkObjectGraph(ObjectScanner objectScanner) {
        universe.getFields().forEach(field -> unknownFieldHandler.handleUnknownValueField(this, field));
        universe.getTypes().stream().filter(AnalysisType::isReachable).forEach(dynamicHubInitializer::initializeMetaData);

        /* Scan hubs of all types that end up in the native image. */
        universe.getTypes().stream().filter(AnalysisType::isReachable).forEach(type -> scanHub(objectScanner, type));
    }

    @Override
    public SVMHost getHostVM() {
        return (SVMHost) hostVM;
    }

    @Override
    public void cleanupAfterAnalysis() {
        super.cleanupAfterAnalysis();
        unknownFieldHandler.cleanupAfterAnalysis();
    }

    @Override
    public void checkUserLimitations() {
        int maxReachableTypes = MaxReachableTypes.getValue();
        if (maxReachableTypes >= 0) {
            CallTreePrinter callTreePrinter = new CallTreePrinter(this);
            callTreePrinter.buildCallTree();
            int numberOfTypes = callTreePrinter.classesSet(false).size();
            if (numberOfTypes > maxReachableTypes) {
                throw UserError.abort("Reachable %d types but only %d allowed (because the %s option is set). To see all reachable types use %s; to change the maximum number of allowed types use %s.",
                                numberOfTypes, maxReachableTypes, MaxReachableTypes.getName(), PrintAnalysisCallTree.getName(), MaxReachableTypes.getName());
            }
        }
    }

    @Override
    public AnnotationSubstitutionProcessor getAnnotationSubstitutionProcessor() {
        return annotationSubstitutionProcessor;
    }

    private void scanHub(ObjectScanner objectScanner, AnalysisType type) {
        SVMHost svmHost = (SVMHost) hostVM;
        JavaConstant hubConstant = SubstrateObjectConstant.forObject(svmHost.dynamicHub(type));
        objectScanner.scanConstant(hubConstant, ScanReason.HUB);
    }

    public static ResolvedJavaType toWrappedType(ResolvedJavaType type) {
        if (type instanceof AnalysisType) {
            return ((AnalysisType) type).getWrappedWithoutResolve();
        } else if (type instanceof HostedType) {
            return ((HostedType) type).getWrapped().getWrappedWithoutResolve();
        } else {
            return type;
        }
    }

    @Override
    public boolean trackConcreteAnalysisObjects(AnalysisType type) {
        /*
         * For classes marked as UnknownClass no context sensitive analysis is done, i.e., no
         * concrete objects are tracked.
         *
         * It is assumed that an object of type C can be of any type compatible with C. At the same
         * type fields of type C can be of any type compatible with their declared type.
         */

        return !SVMHost.isUnknownClass(type);
    }

    /**
     * Builds a pattern that checks if the tested string starts with any of the target prefixes,
     * like so: {@code ^(str1(.*)|str2(.*)|str3(.*))}.
     */
    private static Pattern buildPrefixMatchPattern(String[] targetPrefixes) {
        StringBuilder patternStr = new StringBuilder("^(");
        for (int i = 0; i < targetPrefixes.length; i++) {
            String prefix = targetPrefixes[i];
            patternStr.append(prefix);
            patternStr.append("(.*)");
            if (i < targetPrefixes.length - 1) {
                patternStr.append("|");
            }
        }
        patternStr.append(')');
        return Pattern.compile(patternStr.toString());
    }

    @Override
    public boolean isCallAllowed(PointsToAnalysis bb, AnalysisMethod caller, AnalysisMethod callee, NodeSourcePosition srcPosition) {
        String calleeName = callee.getQualifiedName();
        if (illegalCalleesPattern.matcher(calleeName).find()) {
            String callerName = caller.getQualifiedName();
            if (targetCallersPattern.matcher(callerName).find()) {
                SuppressSVMWarnings suppress = caller.getAnnotation(SuppressSVMWarnings.class);
                AnalysisType callerType = caller.getDeclaringClass();
                while (suppress == null && callerType != null) {
                    suppress = callerType.getAnnotation(SuppressSVMWarnings.class);
                    callerType = callerType.getEnclosingType();
                }
                if (suppress != null) {
                    String[] reasons = suppress.value();
                    for (String r : reasons) {
                        if (r.equals("AllowUseOfStreamAPI")) {
                            return true;
                        }
                    }
                }
                String message = "Illegal: Graal/Truffle use of Stream API: " + calleeName;
                int bci = srcPosition.getBCI();
                String trace = caller.asStackTraceElement(bci).toString();
                bb.getUnsupportedFeatures().addMessage(callerName, caller, message, trace);
                return false;
            }
        }
        return true;
    }

    @Override
    public SubstrateReplacements getReplacements() {
        return (SubstrateReplacements) super.getReplacements();
    }
}
