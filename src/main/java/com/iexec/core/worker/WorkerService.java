package com.iexec.core.worker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import static com.iexec.core.utils.DateTimeUtils.addMinutesToDate;

@Slf4j
@Service
public class WorkerService {

    private WorkerRepository workerRepository;

    public WorkerService(WorkerRepository workerRepository) {
        this.workerRepository = workerRepository;
    }

    public Optional<Worker> getWorker(String workerName) {
        return workerRepository.findByName(workerName);
    }

    public Worker addWorker(Worker worker) {
        Optional<Worker> optional = workerRepository.findByName(worker.getName());
        if (optional.isPresent()) {
            log.info("The worker is already registered [workerId:{}]", optional.get().getId());
            return optional.get();
        } else {
            Worker newWorker = workerRepository.save(worker);
            log.info("A new worker has been registered [workerId:{}]", newWorker.getId());
            return newWorker;
        }
    }

    public Optional<Worker> updateLastAlive(String name) {
        Optional<Worker> optional = workerRepository.findByName(name);
        if (optional.isPresent()) {
            Worker worker = optional.get();
            worker.setLastAliveDate(new Date());
            workerRepository.save(worker);
            return Optional.of(worker);
        }

        return Optional.empty();
    }

    public Optional<Worker> addTaskIdToWorker(String taskId, String workerName) {
        Optional<Worker> optional = workerRepository.findByName(workerName);
        if (optional.isPresent()) {
            Worker worker = optional.get();
            worker.addTaskId(taskId);
            log.info("Added taskId to worker [taskId:{}, workerName:{}]", taskId, workerName);
            return Optional.of(workerRepository.save(worker));
        }
        return Optional.empty();
    }

    public Optional<Worker> removeTaskIdFromWorker(String taskId, String workerName) {
        Optional<Worker> optional = workerRepository.findByName(workerName);
        if (optional.isPresent()) {
            Worker worker = optional.get();
            worker.removeTaskId(taskId);
            log.info("Removed taskId from worker [taskId:{}, workerName:{}]", taskId, workerName);
            return Optional.of(workerRepository.save(worker));
        }
        return Optional.empty();
    }


    // worker is considered lost if it didn't ping for 1 minute
    public List<Worker> getLostWorkers() {
        Date oneMinuteAgo = addMinutesToDate(new Date(), -1);
        return workerRepository.findByLastAliveDateBefore(oneMinuteAgo);
    }
}