package io.github.yoavgeva.designguard.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;

/**
 * The instructions of a method's catch blocks: those that every path to goes through the start of a handler that
 * catches a type, as the code of {@code catch (IOException e) { ... }} does. The code after the try statement, which
 * the try block reaches too, isn't one, nor is a {@code finally} block's, which runs on every path.
 */
final class CatchBlocks {

    private CatchBlocks() {
    }

    /**
     * Finds the instructions in the method's catch blocks.
     *
     * @param owner  the internal name of the method's class
     * @param method the method
     * @return the instructions; none if it catches nothing, or its code can't be analyzed
     */
    static Set<AbstractInsnNode> in(String owner, MethodNode method) {
        List<Integer> handlers = method.tryCatchBlocks.stream()
                .filter(block -> block.type != null)
                .map(block -> method.instructions.indexOf(block.handler))
                .distinct()
                .toList();
        if (handlers.isEmpty()) {
            return Set.of();
        }
        List<List<Integer>> successors = successors(owner, method);
        Set<Integer> reached = reachable(successors, -1);
        return handlers.stream()
                .flatMap(handler -> {
                    Set<Integer> past = reachable(successors, handler);
                    return reached.stream().filter(index -> !past.contains(index));
                })
                .map(method.instructions::get)
                .collect(Collectors.toSet());
    }

    /** Where control flows from each instruction, by index, normally or by an exception. */
    private static List<List<Integer>> successors(String owner, MethodNode method) {
        List<List<Integer>> edges = IntStream.range(0, method.instructions.size())
                .mapToObj(index -> (List<Integer>) new ArrayList<Integer>())
                .toList();
        Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter()) {
            @Override
            protected void newControlFlowEdge(int instruction, int successor) {
                edges.get(instruction).add(successor);
            }

            @Override
            protected boolean newControlFlowExceptionEdge(int instruction, int successor) {
                edges.get(instruction).add(successor);
                return true;
            }
        };
        try {
            analyzer.analyze(owner, method);
        } catch (AnalyzerException e) {
            return List.of();
        }
        return edges;
    }

    /** The instructions reachable from the method's first without passing the excluded one. */
    private static Set<Integer> reachable(List<List<Integer>> successors, int excluded) {
        Set<Integer> found = new HashSet<>();
        Deque<Integer> pending = new ArrayDeque<>(List.of(0));
        while (!pending.isEmpty()) {
            int index = pending.pop();
            if (index != excluded && index < successors.size() && found.add(index)) {
                successors.get(index).forEach(pending::push);
            }
        }
        return found;
    }
}
