package com.iexec.core.task;

import com.iexec.common.replicate.ReplicateStatus;
import com.iexec.core.pubsub.NotificationService;
import com.iexec.core.replicate.Replicate;
import org.hibernate.validator.constraints.br.TituloEleitoral;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class TaskServiceTests {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private TaskService taskService;

    @Before
    public void init() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void shouldGetNothing() {
        when(taskRepository.findById("dummyId")).thenReturn(Optional.empty());
        Optional<Task> task = taskService.getTask("dummyId");
        assertThat(task.isPresent()).isFalse();
    }

    @Test
    public void shouldGetOneResult() {
        Task task = Task.builder()
                .id("realId")
                .currentStatus(TaskStatus.CREATED)
                .commandLine("commandLine")
                .nbContributionNeeded(2)
                .build();
        when(taskRepository.findById("realId")).thenReturn(Optional.of(task));
        Optional<Task> optional = taskService.getTask("realId");
        assertThat(optional.isPresent()).isTrue();
        assertThat(optional.get()).isEqualTo(task);
    }

    @Test
    public void shouldAddTask() {
        Task task = Task.builder()
                .id("realId")
                .currentStatus(TaskStatus.CREATED)
                .dappName("dappName")
                .commandLine("commandLine")
                .nbContributionNeeded(2)
                .build();
        when(taskRepository.save(any())).thenReturn(task);
        Task saved = taskService.addTask("dappName", "commandLine", 2);
        assertThat(saved).isNotNull();
        assertThat(saved).isEqualTo(task);
    }

    @Test
    public void shouldUpdateReplicateStatus() {
        List<Replicate> replicates = new ArrayList<>();
        replicates.add(new Replicate("worker1", "taskId"));

        List<TaskStatusChange> dateStatusList = new ArrayList<>();
        dateStatusList.add(new TaskStatusChange(TaskStatus.CREATED));

        Task task = Task.builder()
                .id("taskId")
                .currentStatus(TaskStatus.CREATED)
                .commandLine("ls")
                .nbContributionNeeded(1)
                .replicates(replicates)
                .dateStatusList(dateStatusList)
                .build();

        when(taskRepository.findById("taskId")).thenReturn(Optional.of(task));
        when(taskRepository.save(task)).thenReturn(task);
        Optional<Replicate> updated = taskService.updateReplicateStatus("taskId", "worker1", ReplicateStatus.RUNNING);
        assertThat(updated.isPresent()).isTrue();
        assertEquals(2, updated.get().getStatusChangeList().size());
        assertThat(updated.get().getStatusChangeList().get(0).getStatus()).isEqualTo(ReplicateStatus.CREATED);
        assertThat(updated.get().getStatusChangeList().get(1).getStatus()).isEqualTo(ReplicateStatus.RUNNING);
    }

    // some replicates in RUNNING
    @Test
    public void shouldUpdateToRunningCase1() {
        List<Replicate> replicates = new ArrayList<>();
        replicates.add(new Replicate("worker1", "taskId"));
        replicates.add(new Replicate("worker2", "taskId"));
        replicates.add(new Replicate("worker3", "taskId"));
        replicates.get(1).updateStatus(ReplicateStatus.RUNNING);
        replicates.get(2).updateStatus(ReplicateStatus.RUNNING);

        List<TaskStatusChange> dateStatusList = new ArrayList<>();
        dateStatusList.add(new TaskStatusChange(TaskStatus.CREATED));

        Task task = Task.builder()
                .id("taskId")
                .currentStatus(TaskStatus.CREATED)
                .commandLine("ls")
                .nbContributionNeeded(2)
                .replicates(replicates)
                .dateStatusList(dateStatusList)
                .build();

        taskService.tryUpdateToRunning(task);
        assertThat(task.getCurrentStatus()).isEqualTo(TaskStatus.RUNNING);
    }

    // some replicates in RUNNING and COMPUTED
    @Test
    public void shouldUpdateToRunningCase2() {
        List<Replicate> replicates = new ArrayList<>();
        replicates.add(new Replicate("worker1", "taskId"));
        replicates.add(new Replicate("worker2", "taskId"));
        replicates.add(new Replicate("worker3", "taskId"));
        replicates.get(1).updateStatus(ReplicateStatus.COMPUTED);
        replicates.get(2).updateStatus(ReplicateStatus.RUNNING);

        List<TaskStatusChange> dateStatusList = new ArrayList<>();
        dateStatusList.add(new TaskStatusChange(TaskStatus.CREATED));

        Task task = Task.builder()
                .id("taskId")
                .currentStatus(TaskStatus.CREATED)
                .commandLine("ls")
                .nbContributionNeeded(2)
                .replicates(replicates)
                .dateStatusList(dateStatusList)
                .build();

        taskService.tryUpdateToRunning(task);
        assertThat(task.getCurrentStatus()).isEqualTo(TaskStatus.RUNNING);
    }

    // all replicates in CREATED
    @Test
    public void shouldNotUpdateToRunningCase1() {
        List<Replicate> replicates = new ArrayList<>();
        replicates.add(new Replicate("worker1", "taskId"));
        replicates.add(new Replicate("worker2", "taskId"));
        replicates.add(new Replicate("worker3", "taskId"));

        List<TaskStatusChange> dateStatusList = new ArrayList<>();
        dateStatusList.add(new TaskStatusChange(TaskStatus.CREATED));

        Task task = Task.builder()
                .id("taskId")
                .currentStatus(TaskStatus.CREATED)
                .commandLine("ls")
                .nbContributionNeeded(2)
                .replicates(replicates)
                .dateStatusList(dateStatusList)
                .build();

        taskService.tryUpdateToRunning(task);
        assertThat(task.getCurrentStatus()).isNotEqualTo(TaskStatus.RUNNING);
    }


    // Two replicates in COMPUTED BUT nbContributionNeeded = 2, so the task should not be able to move directly from
    // CREATED to COMPUTED
    @Test
    public void shouldNotUpdateToRunningCase2() {
        List<Replicate> replicates = new ArrayList<>();
        replicates.add(new Replicate("worker1", "taskId"));
        replicates.add(new Replicate("worker2", "taskId"));
        replicates.add(new Replicate("worker3", "taskId"));
        replicates.get(1).updateStatus(ReplicateStatus.COMPUTED);
        replicates.get(2).updateStatus(ReplicateStatus.COMPUTED);

        List<TaskStatusChange> dateStatusList = new ArrayList<>();
        dateStatusList.add(new TaskStatusChange(TaskStatus.CREATED));

        Task task = Task.builder()
                .id("taskId")
                .currentStatus(TaskStatus.CREATED)
                .commandLine("ls")
                .nbContributionNeeded(2)
                .replicates(replicates)
                .dateStatusList(dateStatusList)
                .build();

        taskService.tryUpdateToRunning(task);
        assertThat(task.getCurrentStatus()).isNotEqualTo(TaskStatus.RUNNING);
    }

    @Test
    public void shouldUpdateToComputedAndResultRequest() {
        List<Replicate> replicates = new ArrayList<>();
        replicates.add(new Replicate("worker1", "taskId"));
        replicates.add(new Replicate("worker2", "taskId"));
        replicates.add(new Replicate("worker3", "taskId"));
        replicates.get(1).updateStatus(ReplicateStatus.COMPUTED);
        replicates.get(2).updateStatus(ReplicateStatus.COMPUTED);

        List<TaskStatusChange> dateStatusList = new ArrayList<>();
        dateStatusList.add(new TaskStatusChange(TaskStatus.CREATED));

        Task task = Task.builder()
                .id("taskId")
                .currentStatus(TaskStatus.CREATED)
                .commandLine("ls")
                .nbContributionNeeded(2)
                .replicates(replicates)
                .dateStatusList(dateStatusList)
                .build();
        task.setCurrentStatus(TaskStatus.RUNNING);
        when(taskRepository.save(task)).thenReturn(task);

        taskService.tryUpdateToComputedAndResultRequest(task);
        TaskStatus lastButOneStatus = task.getDateStatusList().get(task.getDateStatusList().size() - 2).getStatus();
        assertThat(lastButOneStatus).isEqualTo(TaskStatus.COMPUTED);
        assertThat(task.getCurrentStatus()).isEqualTo(TaskStatus.UPLOAD_RESULT_REQUESTED);
    }

    // not enough COMPUTED replicates
    @Test
    public void shouldNotUpdateToComputedAndResultRequest() {
        List<Replicate> replicates = new ArrayList<>();
        replicates.add(new Replicate("worker1", "taskId"));
        replicates.add(new Replicate("worker2", "taskId"));
        replicates.add(new Replicate("worker3", "taskId"));
        replicates.get(1).updateStatus(ReplicateStatus.COMPUTED);
        replicates.get(2).updateStatus(ReplicateStatus.RUNNING);

        List<TaskStatusChange> dateStatusList = new ArrayList<>();
        dateStatusList.add(new TaskStatusChange(TaskStatus.CREATED));

        Task task = Task.builder()
                .id("taskId")
                .currentStatus(TaskStatus.CREATED)
                .commandLine("ls")
                .nbContributionNeeded(2)
                .replicates(replicates)
                .dateStatusList(dateStatusList)
                .build();
        task.setCurrentStatus(TaskStatus.RUNNING);
        when(taskRepository.save(task)).thenReturn(task);

        taskService.tryUpdateToComputedAndResultRequest(task);
        assertThat(task.getCurrentStatus()).isNotEqualTo(TaskStatus.UPLOAD_RESULT_REQUESTED);
    }

    // at least one UPLOADED
    @Test
    public void shouldUpdateToUploadingResult() {
        List<Replicate> replicates = new ArrayList<>();
        replicates.add(new Replicate("worker1", "taskId"));
        replicates.add(new Replicate("worker2", "taskId"));
        replicates.add(new Replicate("worker3", "taskId"));
        replicates.get(1).updateStatus(ReplicateStatus.COMPUTED);
        replicates.get(2).updateStatus(ReplicateStatus.RESULT_UPLOADED);

        List<TaskStatusChange> dateStatusList = new ArrayList<>();
        dateStatusList.add(new TaskStatusChange(TaskStatus.CREATED));

        Task task = Task.builder()
                .id("taskId")
                .currentStatus(TaskStatus.CREATED)
                .commandLine("ls")
                .nbContributionNeeded(2)
                .replicates(replicates)
                .dateStatusList(dateStatusList)
                .build();
        task.setCurrentStatus(TaskStatus.RUNNING);
        task.setCurrentStatus(TaskStatus.UPLOAD_RESULT_REQUESTED);
        task.setCurrentStatus(TaskStatus.UPLOADING_RESULT);
        task.setCurrentStatus(TaskStatus.RESULT_UPLOADED);
        task.setCurrentStatus(TaskStatus.COMPLETED);

        when(taskRepository.save(task)).thenReturn(task);

        taskService.tryUpdateToResultUploaded(task);
        TaskStatus lastButOneStatus = task.getDateStatusList().get(task.getDateStatusList().size() - 2).getStatus();
        assertThat(lastButOneStatus).isEqualTo(TaskStatus.RESULT_UPLOADED);
        assertThat(task.getCurrentStatus()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    public void shouldNotUpdateToResultUploaded() {
        List<Replicate> replicates = new ArrayList<>();
        replicates.add(new Replicate("worker1", "taskId"));
        replicates.add(new Replicate("worker2", "taskId"));
        replicates.add(new Replicate("worker3", "taskId"));
        replicates.get(1).updateStatus(ReplicateStatus.COMPUTED);
        replicates.get(2).updateStatus(ReplicateStatus.COMPUTED);

        List<TaskStatusChange> dateStatusList = new ArrayList<>();
        dateStatusList.add(new TaskStatusChange(TaskStatus.CREATED));

        Task task = Task.builder()
                .id("taskId")
                .currentStatus(TaskStatus.CREATED)
                .commandLine("ls")
                .nbContributionNeeded(2)
                .replicates(replicates)
                .dateStatusList(dateStatusList)
                .build();
        task.setCurrentStatus(TaskStatus.RUNNING);
        task.setCurrentStatus(TaskStatus.COMPLETED);
        task.setCurrentStatus(TaskStatus.UPLOAD_RESULT_REQUESTED);

        when(taskRepository.save(task)).thenReturn(task);

        taskService.tryUpdateToResultUploaded(task);
        assertThat(task.getCurrentStatus()).isNotEqualTo(TaskStatus.RESULT_UPLOADED);
        assertThat(task.getCurrentStatus()).isNotEqualTo(TaskStatus.COMPLETED);
    }
}