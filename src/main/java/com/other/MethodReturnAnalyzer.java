package com.other;

import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;

public class MethodReturnAnalyzer {

    private static final Map<String, List<String>> results = new LinkedHashMap<>();
    private static final Pattern EMPTY_BLOCK_PATTERN = Pattern.compile("\\{\\s*\\}");

    public static void main(String[] args) {


        String rootPath = "/Users/szld2403225/IdeaProjects/hytech-payment"; // ✅ 修改为你的项目路径

        File rootDir = new File(rootPath);
        if (!rootDir.exists() || !rootDir.isDirectory()) {
            System.err.println("Invalid directory: " + rootPath);
            return;
        }

        try (Stream<Path> paths = Files.walk(Paths.get(rootPath))) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".java"))
                 .forEach(MethodReturnAnalyzer::analyzeFile);
        } catch (IOException e) {
            e.printStackTrace();
        }

        printResults();
    }

    private static void analyzeFile(Path filePath) {
        try {
            List<String> lines = Files.readAllLines(filePath);
            String fullContent = String.join("\n", lines);


            // ✅ 设置语言级别为 JAVA_17
            ParserConfiguration config = new ParserConfiguration();
            config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17); // ✅ 关键！
            JavaParser parser = new JavaParser(config);

            // ✅ 解析文件
            try {
                CompilationUnit cu = parser.parse(fullContent).getResult().get();
                // 使用自定义访问器
                new ClassMethodVisitor(filePath.toString().replace("\\", "/"), lines).visit(cu, null);
            } catch (Exception e) {
                System.err.println("Parse error: " + e.getMessage());
            }
        } catch (Exception e) {
            System.err.println("Error parsing file: " + filePath + " -> " + e.getMessage());
        }
    }

    /**
     * 只访问 class 中的方法，忽略 interface、enum
     */
    static class ClassMethodVisitor extends VoidVisitorAdapter<Void> {
        private final String filePath;
        private final List<String> fileLines;

        public ClassMethodVisitor(String filePath, List<String> fileLines) {
            this.filePath = filePath;
            this.fileLines = fileLines;
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration n, Void arg) {
            // ✅ 只处理 class，跳过 interface
            if (n.isInterface()) {
                return; // 跳过接口
            }

            // ✅ 继续访问 class 中的内容（包括方法）
            super.visit(n, arg);
        }

        @Override
        public void visit(EnumDeclaration n, Void arg) {
            // ✅ 跳过 enum
            // 不调用 super，直接跳过
        }

        @Override
        public void visit(RecordDeclaration n, Void arg) {
            // ✅ 跳过 record（Java 14+）
            // 不处理
        }

        @Override
        public void visit(MethodDeclaration n, Void arg) {
            // 只有在 class 中的方法才会到达这里
            // ✅ 跳过 abstract 方法
            if (n.hasModifier(Modifier.Keyword.ABSTRACT)) {
                // System.out.println("Skipping abstract method: " + n.getNameAsString());
                return;
            }
            
            Range range = n.getRange().orElse(null);
            int line = range != null ? range.begin.line : -1;
            
            String methodName = n.getNameAsString();
            String signature = line + " " + n.getType().asString() + " " + methodName + "()";

            // 获取方法体
            if (n.getBody().isEmpty()) {
                addResult(filePath,  signature + " -> EMPTY BODY (no block)");
                return;
            }

            BlockStmt body = n.getBody().get();
            List<Statement> statements = body.getStatements();

            // 获取方法体行范围
            int beginLine = body.getBegin().get().line;
            int endLine = body.getEnd().get().line;

            StringBuilder bodyContent = new StringBuilder();
            for (int i = beginLine - 1; i < endLine; i++) {
                if (i < fileLines.size()) {
                    bodyContent.append(fileLines.get(i)).append("\n");
                }
            }
            String rawBody = bodyContent.toString().trim();

            // 1. 检查是否被注释掉
            if (isCommentedOut(rawBody)) {
                addResult(filePath,  signature + " -> FULLY COMMENTED OUT");
                return;
            }

            // 2. 检查是否为空块 {}
            if (body.getStatements().isEmpty()) {
                addResult(filePath,  signature + " -> empty {}");
                return;
            }

            // 3. 检查单 return 语句
            if (statements.size() == 1 && statements.get(0) instanceof ReturnStmt) {
                ReturnStmt ret = (ReturnStmt) statements.get(0);
                Expression expr = ret.getExpression().orElse(null);

                if (expr == null) {
                    addResult(filePath,  signature + " -> return; (void)");
                } else if (expr instanceof NullLiteralExpr) {
                    addResult(filePath,  signature + " -> returns null");
                } else if (expr instanceof StringLiteralExpr && "".equals(((StringLiteralExpr) expr).getValue())) {
                    addResult(filePath,  signature + " -> returns \"\"");
                } else if (expr instanceof BooleanLiteralExpr) {
                    boolean value = ((BooleanLiteralExpr) expr).getValue();
                    addResult(filePath,  signature + " -> returns " + value);
                } else if (expr instanceof IntegerLiteralExpr) {
                    String intValue = ((IntegerLiteralExpr) expr).getValue();
                    if (Set.of("0", "0L", "0l").contains(intValue)) {
                        addResult(filePath,  signature + " -> returns zero (" + intValue + ")");
                    }
                } else if (expr instanceof DoubleLiteralExpr) {
                    String val = expr.toString();
                    if ("0.0".equals(val) || "0.0f".equals(val) || "0.0d".equals(val)) {
                        addResult(filePath,  signature + " -> returns zero (" + val + ")");
                    }
                }
            }
        }

        /**
         * 判断方法体是否被完全注释
         */
        private boolean isCommentedOut(String content) {
            String stripped = content.replaceAll("/\\*.*?\\*/", "")
                                    .replaceAll("//.*", "")
                                    .replaceAll("\\s+", "");
            return !stripped.isEmpty() && !stripped.equals("{}") && 
                   (content.trim().startsWith("/*") || content.trim().startsWith("//"));
        }
    }

    private static void addResult(String filePath, String info) {
        results.computeIfAbsent(filePath, k -> new ArrayList<>()).add(info);
    }

    private static void printResults() {
        System.out.println("=== 方法返回值分析结果（仅 class）===\n");

        if (results.isEmpty()) {
            System.out.println("✅ 未发现目标方法。");
            return;
        }

        for (Map.Entry<String, List<String>> entry : results.entrySet()) {
            System.out.println("📄 文件: " + entry.getKey());
            for (String method : entry.getValue()) {
                System.out.println("  🔹 " + method);
            }
            System.out.println();
        }

        System.out.println("✅ 分析完成，共发现 " + results.size() + " 个文件包含目标方法。");
    }
}