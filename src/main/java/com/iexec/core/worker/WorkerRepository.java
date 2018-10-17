package com.iexec.core.worker;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.Date;
import java.util.List;
import java.util.Optional;

interface WorkerRepository extends MongoRepository<Worker, String> {

    Optional<Worker> findByName(String name);

    @Query("{'lastAliveDate': {$lt: ?0}}")
    List<Worker> findByLastAliveDateBefore(Date date);
}