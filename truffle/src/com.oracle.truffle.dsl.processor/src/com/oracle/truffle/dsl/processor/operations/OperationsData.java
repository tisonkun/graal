package com.oracle.truffle.dsl.processor.operations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.TypeElement;

import com.oracle.truffle.dsl.processor.ProcessorContext;
import com.oracle.truffle.dsl.processor.model.Template;

public class OperationsData extends Template {

    private final OperationsBuilder builder = new OperationsBuilder();

    public OperationsData(ProcessorContext context, TypeElement templateType, AnnotationMirror annotation) {
        super(context, templateType, annotation);
    }

    public OperationsBuilder getOperationsBuilder() {
        return builder;
    }

    public Collection<Instruction> getInstructions() {
        return builder.instructions;
    }

    public Collection<Operation.Custom> getCustomOperations() {
        return builder.operations.stream()//
                        .filter(x -> x instanceof Operation.Custom)//
                        .map(x -> (Operation.Custom) x)//
                        .toList();
    }

    public Collection<Operation> getOperations() {
        return builder.operations;
    }

}