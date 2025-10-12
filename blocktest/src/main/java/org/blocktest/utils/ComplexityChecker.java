package org.blocktest.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.*;

public class ComplexityChecker {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java ComplexityChecker <source-file> <line-number>");
            System.exit(1);
        }
        String sourceFile = args[0];
        if (sourceFile.endsWith(".java") && args.length < 2) {
            System.err.println("Usage: java ComplexityChecker <source-file> <line-number>");
            System.exit(1);
        }

        if (sourceFile.endsWith(".java")) {
            int lineNumber = Integer.parseInt(args[1]);
            int guards = analyzeStatement(lineNumber, sourceFile);
            System.out.println(sourceFile + ":" + lineNumber + "," + guards);
        } else {
            try (BufferedReader reader = new BufferedReader(new FileReader(sourceFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sourceFile = line.split(",")[0];
                    int lineNumber = Integer.parseInt(line.split(",")[1]);
                    int guards = analyzeStatement(lineNumber, sourceFile);
                    System.out.println(sourceFile + ":" + lineNumber + "," + guards);
                }
            } catch (Exception ignored) {}
        }
    }


    public static int analyzeStatement(int lineNumber, String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            return -1;
        }

        JavaParser parser = new JavaParser();
        CompilationUnit cu;
        try {
            cu = parser.parse(Files.newInputStream(file.toPath())).getResult().get();
        } catch (Exception e) {
            return -2;
        }

        Expression finestStatement = findFinestStatementAtLine(cu, lineNumber);

        if (finestStatement != null) {
            return countGuardBlocks(finestStatement);
        } else {
            return -3;
        }
    }

    private static Expression findFinestStatementAtLine(CompilationUnit cu, int lineNumber) {
        // Find all statements that contain the target line
        List<Expression> candidateStatements = new ArrayList<Expression>();

        // Use findAll to get all Statement nodes
        // findAll(Class<T> clazz) returns List<T> - finds all descendants of the given type
        List<Expression> allStatements = cu.findAll(Expression.class);

        for (Expression stmt : allStatements) {
            if (stmt.getRange().isPresent()) {
                int startLine = stmt.getRange().get().begin.line;
                int endLine = stmt.getRange().get().end.line;

                // Check if the target line is within this statement
                if (lineNumber >= startLine && lineNumber <= endLine) {
                    candidateStatements.add(stmt);
                }
            }
        }

        // Find the finest (smallest) statement
        Expression finestStatement = null;
        int smallestSize = Integer.MAX_VALUE;

        for (Expression stmt : candidateStatements) {
            if (stmt.getRange().isPresent()) {
                int startLine = stmt.getRange().get().begin.line;
                int endLine = stmt.getRange().get().end.line;
                int size = endLine - startLine + 1;

                if (size < smallestSize) {
                    smallestSize = size;
                    finestStatement = stmt;
                }
            }
        }

        return finestStatement;
    }



    private static int countGuardBlocks(Node node) {
        int count = 0;
        Optional<Node> parentOpt = node.getParentNode();

        while (parentOpt.isPresent()) {
            Node parent = parentOpt.get();
            if (isGuardBlock(parent)) {
                count++;
            }
            parentOpt = parent.getParentNode();
        }

        return count;
    }

    private static boolean isGuardBlock(Node node) {
        return node instanceof IfStmt ||
                node instanceof WhileStmt ||
                node instanceof ForStmt ||
                node instanceof ForEachStmt ||
                node instanceof DoStmt ||
                node instanceof SwitchStmt ||
                node instanceof CatchClause;
        // Note: TryStmt and SynchronizedStmt are NOT guard blocks because they don't have conditions to enter them
    }
}
