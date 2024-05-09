package com.webank.wecross;

import com.webank.wecross.config.StubManagerConfig;
import com.webank.wecross.stub.StubFactory;
import com.webank.wecross.stubmanager.StubManager;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class Generator {
    private static final int ARGS_LENGTH = 3;
    private static ApplicationContext context;

    public static void connectionChain(String type, String path) throws Exception {
        context = new AnnotationConfigApplicationContext(StubManagerConfig.class);
        StubManager stubManager = context.getBean(StubManager.class);
        StubFactory stubFactory = stubManager.getStubFactory(type);
        stubFactory.generateConnection(path, new String[] {});
    }

    public static void addChainAccount(String type, String path) throws Exception {
        context = new AnnotationConfigApplicationContext(StubManagerConfig.class);
        StubManager stubManager = context.getBean(StubManager.class);
        StubFactory stubFactory = stubManager.getStubFactory(type);
        stubFactory.generateAccount(path, new String[] {});
    }

    public static void main(String[] args) {
        if (args.length < ARGS_LENGTH) {
            System.out.println("Usage: connection/account <type> <path> <args>");
            return;
        }

        String op = args[0];
        String type = args[1];
        String path = args[2];
        System.out.println(String.format("operator: " + op + " type: " + type + " path: " + path));

        try {
            if (op.equals("connection")) {
                connectionChain(type, path);
            } else if (op.equals("account")) {
                addChainAccount(type, path);
            } else {
                System.err.println("Unknown operation: " + op);
            }
        } catch (Exception e) {
            System.err.println("Error" + e.toString());
            return;
        }
    }
}
