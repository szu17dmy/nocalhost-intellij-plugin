package dev.nocalhost.plugin.intellij.ui.console;

import com.google.common.collect.Lists;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.jediterm.pty.PtyProcessTtyConnector;
import com.jediterm.terminal.TtyConnector;
import com.pty4j.PtyProcess;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.plugins.terminal.JBTerminalSystemSettingsProvider;
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import javax.swing.*;

import dev.nocalhost.plugin.intellij.api.data.Application;
import dev.nocalhost.plugin.intellij.api.data.DevSpace;
import dev.nocalhost.plugin.intellij.commands.KubectlCommand;
import dev.nocalhost.plugin.intellij.commands.data.KubeResource;
import dev.nocalhost.plugin.intellij.commands.data.KubeResourceList;
import dev.nocalhost.plugin.intellij.commands.data.KubeResourceType;
import dev.nocalhost.plugin.intellij.commands.data.NhctlDescribeOptions;
import dev.nocalhost.plugin.intellij.exception.NocalhostExecuteCmdException;
import dev.nocalhost.plugin.intellij.exception.NocalhostNotifier;
import dev.nocalhost.plugin.intellij.ui.StartDevelopContainerChooseDialog;
import dev.nocalhost.plugin.intellij.ui.tree.node.ResourceNode;
import dev.nocalhost.plugin.intellij.utils.KubeConfigUtil;

public class NocalhostTerminalWindow extends NocalhostConsoleWindow {
    private static final Logger LOG = Logger.getInstance(NocalhostTerminalWindow.class);

    private final Project project;
    private final ToolWindow toolWindow;
    private final ResourceNode node;
    private final DevSpace devSpace;

    private JComponent panel;

    private String title;

    public NocalhostTerminalWindow(Project project, ToolWindow toolWindow, DevSpace devSpace, Application application, String deploymentName) {
        this.project = project;
        this.toolWindow = toolWindow;
        this.devSpace = devSpace;
        this.node = null;

        final String kubeconfigPath = KubeConfigUtil.kubeConfigPath(devSpace).toString();

        List<String> args = Lists.newArrayList(
                "nhctl",
                "dev",
                "terminal", application.getContext().getApplicationName(),
                "--deployment", deploymentName,
                "--kubeconfig", kubeconfigPath
        );
        final String cmd = String.join(" ", args.toArray(new String[]{}));
        title = String.format("%s-%s-%s Terminal", devSpace.getNamespace(), application.getContext().getApplicationName(), deploymentName);

        toTerminal(cmd);
    }

    public NocalhostTerminalWindow(Project project, ToolWindow toolWindow, KubeResourceType type, ResourceNode node) {
        this.project = project;
        this.toolWindow = toolWindow;
        this.node = node;
        this.devSpace = node.devSpace();

        final String kubeconfigPath = KubeConfigUtil.kubeConfigPath(node.devSpace()).toString();

        final NhctlDescribeOptions opts = new NhctlDescribeOptions(node.devSpace());
        opts.setDeployment(node.resourceName());
        try {
            List<String> args;
            if (node.getNhctlDescribeService().isDeveloping()) {
                args = Lists.newArrayList(
                        "nhctl",
                        "dev",
                        "terminal", node.application().getContext().getApplicationName(),
                        "--deployment", node.resourceName(),
                        "--kubeconfig", kubeconfigPath,
                        "--namespace", devSpace.getNamespace()
                );
            } else {
                final KubectlCommand kubectlCommand = ServiceManager.getService(KubectlCommand.class);
                final KubeResourceList pods = kubectlCommand.getResourceList("pods", node.getKubeResource().getSpec().getSelector().getMatchLabels(), node.devSpace());

                String podName = null;
                final List<KubeResource> running = pods
                        .getItems()
                        .stream()
                        .filter(KubeResource::canSelector)
                        .collect(Collectors.toList());
                if (running.size() > 0) {
                    List<String> containers = running
                            .stream().map(r -> r.getMetadata().getName()).collect(Collectors.toList());
                    podName = selectContainer(containers);
                }
                if (StringUtils.isBlank(podName)) {
                    return;
                }
                final String containerName = node.getKubeResource().getSpec().getSelector().getMatchLabels().get("app");

                args = Lists.newArrayList(
                        "kubectl",
                        "exec",
                        "-it", podName,
                        "-c", containerName,
                        "--kubeconfig", kubeconfigPath,
                        "--namespace", devSpace.getNamespace(),
                        "--", "sh -c \"clear; (zsh || bash || ash || sh)\""
                );

            }
            final String cmd = String.join(" ", args.toArray(new String[]{}));
            title = String.format("%s-%s-%s Terminal", node.devSpace().getNamespace(), node.application().getContext().getApplicationName(), node.resourceName());

            toTerminal(cmd);
        } catch (IOException | InterruptedException | NocalhostExecuteCmdException e) {
            LOG.error("error occurred while initializing terminal", e);
            NocalhostNotifier.getInstance(project).notifyError("Nocalhost terminal error", "Error occurred while initializing terminal", e.getMessage());
        }
    }

    private String selectContainer(List<String> containers) {
        if (containers.size() > 1) {
            StartDevelopContainerChooseDialog dialog = new StartDevelopContainerChooseDialog(containers);
            if (dialog.showAndGet()) {
                return dialog.getSelectedContainer();
            } else {
                return null;
            }
        } else {
            return containers.get(0);
        }
    }

    private void toTerminal(String cmd) {
        try {
            LocalTerminalDirectRunner localTerminalDirectRunner = LocalTerminalDirectRunner.createTerminalRunner(project);
            PtyProcess ptyProcess = localTerminalDirectRunner.createProcess(project.getBasePath());
            TtyConnector connector = new PtyProcessTtyConnector(ptyProcess, StandardCharsets.UTF_8);
            JBTerminalSystemSettingsProvider settingsProvider = new JBTerminalSystemSettingsProvider();
            ShellTerminalWidget terminal = new ShellTerminalWidget(project, settingsProvider, new TermDisposable());
            Disposer.register(terminal, settingsProvider);
            terminal.start(connector);
            terminal.executeCommand(cmd);
            panel = terminal;
        } catch (ExecutionException | IOException e) {
            LOG.error("error occurred while starting terminal", e);
            NocalhostNotifier.getInstance(project).notifyError("Nocalhost terminal error", "Error occurred while initializing terminal", e.getMessage());
        }
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public JComponent getPanel() {
        return panel;
    }

    protected static final class TermDisposable implements Disposable {
        private volatile boolean myDisposed;

        public TermDisposable() {
        }

        @Override
        public void dispose() {
            myDisposed = true;
        }

        public boolean isDisposed() {
            return myDisposed;
        }
    }
}
