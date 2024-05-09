package com.webank.wecross.utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ToolUtils {

    public static String getFileExtension(File file) {
        return getFileExtension(file.getName());
    }

    public static String getFileExtension(String fileName) {
        int lastIndexOf = fileName.lastIndexOf(".");
        if (lastIndexOf == -1) {
            return "";
        }
        return fileName.substring(lastIndexOf + 1);
    }

    public static void unZip(String zipFilePath, String destDirectory) throws Exception {
        ZipFile zipFile = new ZipFile(zipFilePath);
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String entryName = entry.getName();
            File entryFile = new File(destDirectory, entryName);
            if (entry.isDirectory()) {
                entryFile.mkdirs();
            } else {
                File parent = entryFile.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }

                try (InputStream inputStream = zipFile.getInputStream(entry)) {
                    FileOutputStream outputStream = new FileOutputStream(entryFile);
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = inputStream.read(buffer)) > 0) {
                        outputStream.write(buffer, 0, length);
                    }
                }
            }
        }
        zipFile.close();
    }

    public static boolean executeShell(long waitSeconds, String... command) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        Process process = processBuilder.start();
        boolean completed = process.waitFor(waitSeconds, TimeUnit.SECONDS);
        if (!completed) {
            process.destroy();
        }
        return completed;
    }

    public static void deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }
}
