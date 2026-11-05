package org.levimc.launcher.core.content;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ServerManager {
    private volatile File minecraftPeDir;

    public void setMinecraftPeDirectory(File directory) {
        this.minecraftPeDir = directory;
    }

    public File getServerListFile() {
        File root = minecraftPeDir;
        if (root == null) return null;
        return new File(root, "external_servers.txt");
    }

    public synchronized List<ServerItem> getServers() {
        List<ServerItem> items = new ArrayList<>();
        File serverFile = getServerListFile();
        if (serverFile == null || !serverFile.exists()) return items;

        try (BufferedReader br = new BufferedReader(new FileReader(serverFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(":");
                try {
                    if (parts.length >= 5) {
                        String name = parts[1];
                        String ip = parts[2];
                        int port = Integer.parseInt(parts[3]);
                        items.add(new ServerItem(name, ip, port));
                    }
                } catch (Exception e) {
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return items;
    }

    public synchronized boolean deleteServer(ServerItem serverItem) {
        return deleteServer(serverItem, getServerListFile());
    }

    synchronized boolean deleteServer(ServerItem serverItem, File serverFile) {
        if (serverFile == null || !serverFile.exists()) return false;

        List<String> lines = new ArrayList<>();
        boolean deleted = false;
        try (BufferedReader br = new BufferedReader(new FileReader(serverFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(":");
                if (parts.length >= 5) {
                    String name = parts[1];
                    String ip = parts[2];
                    String portStr = parts[3];
                    if (name.equals(serverItem.name) && ip.equals(serverItem.ip) && portStr.equals(String.valueOf(serverItem.port))) {
                        deleted = true;
                        continue;
                    }
                }
                lines.add(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        if (deleted) {
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(serverFile))) {
                for (String l : lines) {
                    bw.write(l);
                    bw.newLine();
                }
                return true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return false;
    }

    public synchronized boolean addServer(ServerItem serverItem) {
        return addServer(serverItem, getServerListFile());
    }

    synchronized boolean addServer(ServerItem serverItem, File file) {
        if (file == null) return false;

        List<String> lines = new ArrayList<>();
        int maxIndex = 0;
        boolean exists = false;
        if (file.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = br.readLine()) != null) {
                    lines.add(line);
                    String[] parts = line.split(":");
                    if (parts.length >= 5) {
                        String name = parts[1];
                        String ip = parts[2];
                        String portStr = parts[3];
                        try {
                            int idx = Integer.parseInt(parts[0]);
                            if (idx > maxIndex) maxIndex = idx;
                        } catch (NumberFormatException ignored) {}

                        if (name.equals(serverItem.name) && ip.equals(serverItem.ip) && portStr.equals(String.valueOf(serverItem.port))) {
                            exists = true;
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (exists) return false;

        long timestamp = System.currentTimeMillis() / 1000L;
        String newLine = (maxIndex + 1) + ":" + serverItem.name + ":" + serverItem.ip + ":" + serverItem.port + ":" + timestamp;
        lines.add(newLine);

        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file))) {
            for (String l : lines) {
                bw.write(l);
                bw.newLine();
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }
}
