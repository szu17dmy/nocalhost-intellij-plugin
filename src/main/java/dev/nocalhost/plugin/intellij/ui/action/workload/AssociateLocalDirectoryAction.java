package dev.nocalhost.plugin.intellij.ui.action.workload;

import com.google.gson.reflect.TypeToken;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import dev.nocalhost.plugin.intellij.commands.OutputCapturedNhctlCommand;
import dev.nocalhost.plugin.intellij.commands.data.NhctlDevAssociateOptions;
import dev.nocalhost.plugin.intellij.nhctl.NhctlDevContainerListCommand;
import dev.nocalhost.plugin.intellij.task.BaseBackgroundTask;
import dev.nocalhost.plugin.intellij.ui.dialog.ListChooseDialog;
import dev.nocalhost.plugin.intellij.ui.tree.node.ResourceNode;
import dev.nocalhost.plugin.intellij.utils.DataUtils;
import dev.nocalhost.plugin.intellij.utils.ErrorUtil;
import dev.nocalhost.plugin.intellij.utils.FileChooseUtil;
import dev.nocalhost.plugin.intellij.utils.KubeConfigUtil;
import lombok.SneakyThrows;

public class AssociateLocalDirectoryAction extends DumbAwareAction {
    private final OutputCapturedNhctlCommand outputCapturedNhctlCommand;
    private final AtomicReference<String> container = new AtomicReference<>("");

    private final Project project;
    private final ResourceNode node;
    private final Path kubeConfigPath;
    private final String namespace;

    public AssociateLocalDirectoryAction(Project project, ResourceNode resourceNode) {
        super("Associate Local DIR");

        this.project = project;
        this.node = resourceNode;
        this.kubeConfigPath = KubeConfigUtil.kubeConfigPath(node.getClusterNode().getRawKubeConfig());
        this.namespace = node.getNamespaceNode().getNamespace();

        outputCapturedNhctlCommand = project.getService(OutputCapturedNhctlCommand.class);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        List<String> containers;

        try {
            var cmd = new NhctlDevContainerListCommand();
            cmd.setNamespace(namespace);
            cmd.setKubeConfig(kubeConfigPath);
            cmd.setDeployment(node.resourceName());
            cmd.setApplication(node.applicationName());
            cmd.setControllerType(node.getKubeResource().getKind());
            containers = DataUtils.GSON.fromJson(cmd.execute(), TypeToken.getParameterized(List.class, String.class).getType());
        } catch (Exception ex) {
            ErrorUtil.dealWith(project, "Failed to get containers", "Error occurs while get containers", ex);
            return;
        }

        if (containers.size() == 1) {
            container.set(containers.get(0));
        } else {
            var dialog = new ListChooseDialog(project, "Select Container", containers);
            if (dialog.showAndGet()) {
                container.set(dialog.getSelectedValue());
            } else {
                return;
            }
        }

        final Path dir = FileChooseUtil.chooseSingleDirectory(project);
        if (dir == null) {
            return;
        }

        ProgressManager.getInstance().run(new BaseBackgroundTask(project, "Associating project path") {

            @Override
            public void onThrowable(@NotNull Throwable e) {
                ErrorUtil.dealWith(project, "Associating project path error",
                        "Error occurs while associating project path", e);
            }

            @SneakyThrows
            @Override
            public void runTask(@NotNull ProgressIndicator indicator) {
                NhctlDevAssociateOptions opts = new NhctlDevAssociateOptions(kubeConfigPath, namespace, this);
                opts.setLocalSync(dir.toString());
                opts.setContainer(container.get());
                opts.setDeployment(node.resourceName());
                opts.setControllerType(node.getKubeResource().getKind());
                outputCapturedNhctlCommand.devAssociate(node.applicationName(), opts);
            }
        });
    }
}
