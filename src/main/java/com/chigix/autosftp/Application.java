/*
 * This file is part of the autosftp package.
 * 
 * (c) Richard Lea <chigix@zoho.com>
 * 
 * For the full copyright and license information, please view the LICENSE
 * file that was distributed with this source code.
 */
package com.chigix.autosftp;

import com.chigix.autosftp.ssh.SshAddressParser;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import static com.jcraft.jsch.ChannelSftp.SSH_FX_NO_SUCH_FILE;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import java.io.Console;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.UnrecognizedOptionException;
import org.apache.commons.io.monitor.FileAlterationListener;
import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;
import org.apache.commons.lang.StringUtils;

/**
 *
 * @author Richard Lea <chigix@zoho.com>
 */
public class Application {

    private static Session sshSession;
    private static ChannelSftp sftpChannel;
    private static Path remotePath = null;
    private static Path localPath = null;

    public static void main(String[] args) {
        Options options = new Options();
        options.addOption(Option.builder("P").longOpt("port").hasArg().build())
                .addOption(Option.builder("h").longOpt("help").desc("Print this message").build())
                .addOption(Option.builder("i").argName("identity_file").hasArg().build());
        int port = 22;
        CommandLine line;
        try {
            line = new DefaultParser().parse(options, args);
        } catch (UnrecognizedOptionException ex) {
            System.err.println(ex.getMessage());
            return;
        } catch (ParseException ex) {
            Logger.getLogger(Application.class.getName()).log(Level.SEVERE, null, ex);
            return;
        }
        if (line.hasOption("help")) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("autosftp /path/to/watch [user@]host2:[file2]", options, true);
            return;
        }
        String givenPort;
        if (line.hasOption("port") && StringUtils.isNumeric(givenPort = line.getOptionValue("port"))) {
            port = Integer.valueOf(givenPort);
        }
        if (line.getArgs().length < 0) {
            System.err.println("Please provide a path to watch.");
            return;
        }
        localPath = Paths.get(line.getArgs()[0]);
        if (line.getArgs().length < 1) {
            System.err.println("Please provide remote ssh information.");
            return;
        }
        SshAddressParser addressParse;
        try {
            addressParse = new SshAddressParser().parse(line.getArgs()[1]);
        } catch (SshAddressParser.InvalidAddressException ex) {
            System.err.println(ex.getMessage());
            return;
        }
        if (addressParse.getDefaultDirectory() != null) {
            remotePath = Paths.get(addressParse.getDefaultDirectory());
        }
        try {
            sshSession = new JSch().getSession(addressParse.getUsername(), addressParse.getHost(), port);
        } catch (JSchException ex) {
            Logger.getLogger(Application.class.getName()).log(Level.SEVERE, null, ex);
            return;
        }
        try {
            sshOpen();
        } catch (JSchException ex) {
            Logger.getLogger(Application.class.getName()).log(Level.SEVERE, null, ex);
            sshClose();
        }
        System.out.println("Remote Default Path: " + remotePath);
        Runtime.getRuntime().addShutdownHook(new Thread() {

            @Override
            public void run() {
                sshClose();
                System.out.println("Bye~~~");
            }

        });
        try {
            watchDir(Paths.get(line.getArgs()[0]));
        } catch (Exception ex) {
            Logger.getLogger(Application.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public static void watchDir(Path dir) throws Exception {
        // The monitor will perform polling on the folder every 5 seconds
        final long pollingInterval = 5 * 1000;

        File folder = dir.toFile();

        if (!folder.exists()) {
            // Test to see if monitored folder exists
            throw new FileNotFoundException("Directory not found: " + dir);
        }

        FileAlterationObserver observer = new FileAlterationObserver(folder);
        FileAlterationMonitor monitor = new FileAlterationMonitor(pollingInterval);
        FileAlterationListener listener = new FileAlterationListenerAdaptor() {
            // Is triggered when a file is created in the monitored folder
            @Override
            public void onFileCreate(File file) {
                Path relativePath = localPath.toAbsolutePath().normalize()
                        .relativize(file.getAbsoluteFile().toPath().normalize());
                System.out.println("File created: " + relativePath);
                final String destPath = remotePath.resolve(relativePath).normalize()
                        .toString().replace('\\', '/');
                ArrayList<String> lackDirs = new ArrayList<>();
                String tmpParentPath = destPath;
                while (!tmpParentPath.equals("/") && !tmpParentPath.equals("\\")) {
                    tmpParentPath = new File(tmpParentPath).getParentFile().toPath()
                            .toString().replace('\\', '/');
                    try {
                        sftpChannel.cd(tmpParentPath);
                    } catch (SftpException ex) {
                        if (ex.id == SSH_FX_NO_SUCH_FILE) {
                            lackDirs.add(tmpParentPath);
                            continue;
                        }
                        Logger.getLogger(Application.class.getName()).log(Level.SEVERE, null, ex);
                        return;
                    }
                    break;
                }
                for (int i = lackDirs.size() - 1; i > -1; i--) {
                    try {
                        sftpChannel.mkdir(lackDirs.get(i));
                    } catch (SftpException ex) {
                        Logger.getLogger(Application.class.getName()).log(Level.SEVERE, null, ex);
                        System.err.println(destPath + " Creating Fail.");
                        return;
                    }
                }
                try {
                    sftpChannel.put(new FileInputStream(file), destPath, 644);
                } catch (FileNotFoundException ex) {
                    System.out.println("File: " + file.getAbsolutePath() + " not exists.");
                    return;
                } catch (SftpException ex) {
                    Logger.getLogger(Application.class.getName()).log(Level.SEVERE, null, ex);
                    return;
                }
                System.out.println("File Uploaded: " + destPath);
            }

            // Is triggered when a file is deleted from the monitored folder
            @Override
            public void onFileDelete(File file) {
                if (file.exists()) {
                    return;
                }
                Path relativePath = localPath.toAbsolutePath().normalize()
                        .relativize(file.getAbsoluteFile().toPath().normalize());
                System.out.println("File Deleted: " + relativePath);
                final String destPath = remotePath.resolve(relativePath).normalize()
                        .toString().replace('\\', '/');
                try {
                    sftpChannel.rm(destPath);
                } catch (SftpException ex) {
                    Logger.getLogger(Application.class.getName()).log(Level.SEVERE, null, ex);
                }
                System.out.println("Remote Deleted: " + relativePath);
            }

            @Override
            public void onFileChange(File file) {
                this.onFileCreate(file);
            }

        };

        observer.addListener(listener);
        monitor.addObserver(observer);
        monitor.start();
    }

    private static boolean isOpened = false;

    private static void sshOpen() throws JSchException {
        if (isOpened) {
            return;
        }
        isOpened = true;
        Session currentSession = sshSession;
        final Properties sshConfig = new Properties();
        sshConfig.put("StrictHostKeyChecking", "no");
        for (int i = 0; i < 3; i++) {
            currentSession.setConfig(sshConfig);
            try {
                currentSession.connect();
            } catch (JSchException ex) {
                if (ex.getCause() instanceof ConnectException) {
                    System.err.println("ssh: connect to host " + currentSession.getHost() + " port " + currentSession.getPort() + ": " + ex.getCause().getMessage());
                    System.exit(1);
                }
                if (ex.getMessage().equals("Auth fail")) {
                    Console console = System.console();
                    if (console == null) {
                        System.err.println("Couldn't get console instance, Please consider involving identity_file in command line.");
                        System.exit(1);
                    }
                    currentSession.disconnect();
                    char passwordArray[] = console.readPassword("richard@192.168.2.103's password: ");
                    Session newSession = new JSch().getSession(sshSession.getUserName(), sshSession.getHost(), sshSession.getPort());
                    newSession.setConfig(sshConfig);
                    newSession.setPassword(new String(passwordArray));
                    currentSession = newSession;
                    continue;
                }
                Logger.getLogger(Application.class.getName()).log(Level.SEVERE, null, ex);
                return;
            }
            break;
        }
        sshSession = currentSession;
        Channel channel = sshSession.openChannel("sftp");
        channel.connect();
        sftpChannel = (ChannelSftp) channel;
        sftpChannel.setPty(false);
        if (remotePath == null) {
            try {
                remotePath = Paths.get(sftpChannel.pwd());
            } catch (SftpException ex) {
                Logger.getLogger(Application.class.getName()).log(Level.SEVERE, null, ex);
                return;
            }
        }
    }

    private static void sshClose() {
        sftpChannel.exit();
        sshSession.disconnect();
        System.out.println("SSH CLOSED");
    }

}
