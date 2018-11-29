package com.iexec.core.task;

import com.iexec.common.chain.ChainTask;
import com.iexec.common.chain.ChainTaskStatus;
import com.iexec.common.replicate.ReplicateStatus;
import com.iexec.core.chain.IexecHubService;
import com.iexec.core.replicate.Replicate;
import com.iexec.core.replicate.ReplicatesService;
import com.iexec.core.task.event.ConsensusReachedEvent;
import com.iexec.core.task.event.PleaseUploadEvent;
import com.iexec.core.task.event.TaskCompletedEvent;
import com.iexec.core.worker.Worker;
import com.iexec.core.worker.WorkerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static com.iexec.core.task.TaskStatus.*;

@Slf4j
@Service
public class TaskService {

    private TaskRepository taskRepository;
    private WorkerService workerService;
    private IexecHubService iexecHubService;
    private ReplicatesService replicatesService;
    private ApplicationEventPublisher applicationEventPublisher;

    public TaskService(TaskRepository taskRepository,
                       WorkerService workerService,
                       IexecHubService iexecHubService,
                       ReplicatesService replicatesService,
                       ApplicationEventPublisher applicationEventPublisher) {
        this.taskRepository = taskRepository;
        this.workerService = workerService;
        this.iexecHubService = iexecHubService;
        this.replicatesService = replicatesService;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    public Optional<Task> addTask(String chainDealId, int taskIndex, String imageName, String commandLine, int trust) {
        if (getTasksByChainDealIdAndTaskIndex(chainDealId, taskIndex).isEmpty()) {
            log.info("Add new task [chainDealId:{}, taskIndex:{}, imageName:{}, commandLine:{}, trust:{}]",
                    chainDealId, taskIndex, imageName, commandLine, trust);
            return Optional.of(taskRepository.save(new Task(chainDealId, taskIndex, imageName, commandLine, trust)));
        }
        log.error("Task already added [chainDealId:{}, taskIndex:{}, imageName:{}, commandLine:{}, trust:{}]",
                chainDealId, taskIndex, imageName, commandLine, trust);
        return Optional.empty();
    }

    public Optional<Task> getTaskByChainTaskId(String chainTaskId) {
        return taskRepository.findByChainTaskId(chainTaskId);
    }

    public List<Task> findByCurrentStatus(TaskStatus status) {
        return taskRepository.findByCurrentStatus(status);
    }

    private List<Task> getTasksByChainDealIdAndTaskIndex(String chainDealId, int taskIndex) {
        return taskRepository.findByChainDealIdAndTaskIndex(chainDealId, taskIndex);
    }

    private List<Task> getAllRunningTasks() {
        return taskRepository.findByCurrentStatus(Arrays.asList(INITIALIZED, RUNNING));
    }

    // in case the task has been modified between reading and writing it, it is retried up to 5 times
    @Retryable(value = {OptimisticLockingFailureException.class}, maxAttempts = 5)
    Optional<Replicate> getAvailableReplicate(String walletAddress) {
        // return empty if the worker is not registered
        Optional<Worker> optional = workerService.getWorker(walletAddress);
        if (!optional.isPresent()) {
            return Optional.empty();
        }

        // return empty if there is no task to contribute
        List<Task> runningTasks = getAllRunningTasks();
        if (runningTasks.isEmpty()) {
            return Optional.empty();
        }

        // return empty if the worker already has enough running tasks
        if (!workerService.canAcceptMoreWorks(walletAddress)) {
            return Optional.empty();
        }

        for (Task task : runningTasks) {
            String chainTaskId = task.getChainTaskId();

            if (!replicatesService.hasWorkerAlreadyContributed(chainTaskId, walletAddress) &&
                    replicatesService.moreReplicatesNeeded(chainTaskId, task.getTrust())) {
                replicatesService.addNewReplicate(chainTaskId, walletAddress);
                workerService.addChainTaskIdToWorker(chainTaskId, walletAddress);
                return replicatesService.getReplicate(chainTaskId, walletAddress);
            }
        }

        return Optional.empty();
    }

    void tryToMoveTaskToNextStatus(Task task) {
        log.info("Try to move task to next status [chainTaskId:{}, currentStatus:{}]", task.getChainTaskId(), task.getCurrentStatus());
        switch (task.getCurrentStatus()) {
            case RECEIVED:
                received2Initialized(task);
                break;
            case INITIALIZED:
                created2Running(task);
                break;
            case RUNNING:
                running2ConsensusReached(task);
                break;
            case CONSENSUS_REACHED:
                consensusReached2AtLeastOneReveal(task);
                break;
            case RESULT_UPLOAD_REQUESTED:
                uploadRequested2UploadingResult(task);
                break;
            case RESULT_UPLOADING:
                resultUploading2Uploaded(task);
                break;
            case RESULT_UPLOADED:
                tryUpdateFromResultUploadedToFinalize(task);
                break;
            case COMPLETED:
                break;
            case ERROR:
                break;
        }
    }

    private Task updateTaskStatusAndSave(Task task, TaskStatus newStatus) {
        TaskStatus currentStatus = task.getCurrentStatus();
        task.changeStatus(newStatus);
        Task savedTask = taskRepository.save(task);
        log.info("Update task to new status[taskId:{}, currentStatus:{}, newStatus:{}]", task.getId(), currentStatus, newStatus);
        return savedTask;
    }

    private void received2Initialized(Task task) {
        boolean isChainTaskIdEmpty = task.getChainTaskId() != null && task.getChainTaskId().isEmpty();
        boolean isCurrentStatusReceived = task.getCurrentStatus().equals(RECEIVED);

        if (isChainTaskIdEmpty && isCurrentStatusReceived) {
            /*TODO ?
            if (!iexecHubService.canInitializeTask(task.getChainDealId(), task.getTaskIndex())){
                return;
            }*/

            String chainTaskId = iexecHubService.initializeTask(task.getChainDealId(), task.getTaskIndex());
            if (chainTaskId != null && !chainTaskId.isEmpty()) {
                task.setChainTaskId(chainTaskId);
                updateTaskStatusAndSave(task, INITIALIZED);
                replicatesService.createEmptyReplicateList(chainTaskId);
            } else {
                updateTaskStatusAndSave(task, INITIALIZE_FAILED);
            }
        }
    }

    private void created2Running(Task task) {
        String chainTaskId = task.getChainTaskId();
        boolean condition1 = replicatesService.getNbReplicatesWithStatus(chainTaskId, ReplicateStatus.RUNNING, ReplicateStatus.COMPUTED) > 0;
        boolean condition2 = replicatesService.getNbReplicatesWithStatus(chainTaskId, ReplicateStatus.COMPUTED) < task.getTrust();
        boolean condition3 = task.getCurrentStatus().equals(INITIALIZED);

        if (condition1 && condition2 && condition3) {
            updateTaskStatusAndSave(task, RUNNING);
        }
    }


    private void running2ConsensusReached(Task task) {
        boolean isTaskInRunningStatus = task.getCurrentStatus().equals(RUNNING);

        ChainTask chainTask = iexecHubService.getChainTask(task.getChainTaskId());
        boolean isChainTaskRevealing = chainTask.getStatus().equals(ChainTaskStatus.REVEALING);

        int onChainWinners = chainTask.getWinnerCounter();
        int offChainWinners = replicatesService.getNbReplicatesWithStatus(task.getChainTaskId(), ReplicateStatus.CONTRIBUTED);
        boolean offChainWinnersEqualsOnChainWinners = offChainWinners == onChainWinners;

        if (isTaskInRunningStatus && isChainTaskRevealing  && offChainWinnersEqualsOnChainWinners) {
            task.setConsensus(chainTask.getConsensusValue());
            updateTaskStatusAndSave(task, CONSENSUS_REACHED);

            applicationEventPublisher.publishEvent(ConsensusReachedEvent.builder()
                    .chainTaskId(task.getChainTaskId())
                    .consensus(task.getConsensus())
                    .build());
        }
    }

    private void consensusReached2AtLeastOneReveal(Task task) {
        boolean condition1 = task.getCurrentStatus().equals(CONSENSUS_REACHED);
        boolean condition2 = replicatesService.getNbReplicatesWithStatus(task.getChainTaskId(), ReplicateStatus.REVEALED) > 0;

        if (condition1 && condition2) {
            updateTaskStatusAndSave(task, AT_LEAST_ONE_REVEALED);
            requestUpload(task);
        }
    }

    private void uploadRequested2UploadingResult(Task task) {
        boolean condition1 = task.getCurrentStatus().equals(TaskStatus.RESULT_UPLOAD_REQUESTED);
        boolean condition2 = replicatesService.getNbReplicatesWithStatus(task.getChainTaskId(), ReplicateStatus.RESULT_UPLOADING) > 0;

        if (condition1 && condition2) {
            updateTaskStatusAndSave(task, RESULT_UPLOADING);
        }
    }

    private void resultUploading2Uploaded(Task task) {
        boolean condition1 = task.getCurrentStatus().equals(TaskStatus.RESULT_UPLOADING);
        boolean condition2 = replicatesService.getNbReplicatesWithStatus(task.getChainTaskId(), ReplicateStatus.RESULT_UPLOADED) > 0;

        if (condition1 && condition2) {
            updateTaskStatusAndSave(task, RESULT_UPLOADED);
            tryUpdateFromResultUploadedToFinalize(task);
        } else if (replicatesService.getNbReplicatesWithStatus(task.getChainTaskId(), ReplicateStatus.RESULT_UPLOAD_REQUEST_FAILED) > 0 &&
                replicatesService.getNbReplicatesWithStatus(task.getChainTaskId(), ReplicateStatus.RESULT_UPLOADING) == 0) {
            // need to request upload again
            requestUpload(task);
        }
    }

    private void requestUpload(Task task) {

        Optional<Replicate> optionalReplicate = replicatesService.getReplicateWithRevealStatus(task.getChainTaskId());
        if (optionalReplicate.isPresent()) {
            Replicate replicate = optionalReplicate.get();

            applicationEventPublisher.publishEvent(new PleaseUploadEvent(task.getChainTaskId(), replicate.getWalletAddress()));

            // save in the task the workerWallet that is in charge of uploading the result
            task.setUploadingWorkerWalletAddress(replicate.getWalletAddress());
            updateTaskStatusAndSave(task, RESULT_UPLOAD_REQUESTED);
        }
    }

    private void tryUpdateFromResultUploadedToFinalize(Task task) {
        boolean condition1 = task.getCurrentStatus().equals(RESULT_UPLOADED);
        boolean condition2 = iexecHubService.canFinalize(task.getChainTaskId());

        if (condition1 && condition2) {
            updateTaskStatusAndSave(task, FINALIZING);
            if (iexecHubService.finalizeTask(task.getChainTaskId(), "GET /results/" + task.getChainTaskId())) {
                updateTaskStatusAndSave(task, FINALIZED);
                updateFromFinalizedToCompleted(task);
            } else {
                updateTaskStatusAndSave(task, FINALIZE_FAILED);
            }
        }
    }

    private void updateFromFinalizedToCompleted(Task task) {
        if (task.getCurrentStatus().equals(FINALIZED)) {
            updateTaskStatusAndSave(task, COMPLETED);

            String chainTaskId = task.getChainTaskId();
            for (Replicate replicate : replicatesService.getReplicates(chainTaskId)) {
                workerService.removeChainTaskIdFromWorker(chainTaskId, replicate.getWalletAddress());
            }

            applicationEventPublisher.publishEvent(new TaskCompletedEvent(task));
        }
    }

}
