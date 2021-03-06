package com.iexec.core.replicate;

import com.iexec.common.chain.ChainContribution;
import com.iexec.common.chain.ChainContributionStatus;
import com.iexec.common.chain.ChainReceipt;
import com.iexec.common.replicate.ReplicateStatus;
import com.iexec.common.replicate.ReplicateStatusChange;
import com.iexec.common.replicate.ReplicateStatusModifier;
import com.iexec.core.chain.IexecHubService;
import com.iexec.core.chain.Web3jService;
import com.iexec.core.workflow.ReplicateWorkflow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.iexec.common.replicate.ReplicateStatus.*;

@Slf4j
@Service
public class ReplicatesService {

    private ReplicatesRepository replicatesRepository;
    private IexecHubService iexecHubService;
    private ApplicationEventPublisher applicationEventPublisher;
    private Web3jService web3jService;

    public ReplicatesService(ReplicatesRepository replicatesRepository,
                             IexecHubService iexecHubService,
                             ApplicationEventPublisher applicationEventPublisher,
                             Web3jService web3jService) {
        this.replicatesRepository = replicatesRepository;
        this.iexecHubService = iexecHubService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.web3jService = web3jService;
    }

    public void addNewReplicate(String chainTaskId, String walletAddress) {
        if (!getReplicate(chainTaskId, walletAddress).isPresent()) {
            Optional<ReplicatesList> optional = getReplicatesList(chainTaskId);
            if (optional.isPresent()) {
                ReplicatesList replicatesList = optional.get();
                replicatesList.getReplicates().add(new Replicate(walletAddress, chainTaskId));
                replicatesRepository.save(replicatesList);
                log.info("New replicate saved [chainTaskId:{}, walletAddress:{}]", chainTaskId, walletAddress);
            }
        } else {
            log.error("Replicate already saved [chainTaskId:{}, walletAddress:{}]", chainTaskId, walletAddress);
        }

    }

    public synchronized void createEmptyReplicateList(String chainTaskId) {
        replicatesRepository.save(new ReplicatesList(chainTaskId));
    }

    public Optional<ReplicatesList> getReplicatesList(String chainTaskId) {
        return replicatesRepository.findByChainTaskId(chainTaskId);
    }

    public List<Replicate> getReplicates(String chainTaskId) {
        Optional<ReplicatesList> optionalList = getReplicatesList(chainTaskId);
        if (!optionalList.isPresent()) {
            return Collections.emptyList();
        }
        return optionalList.get().getReplicates();
    }

    public Optional<Replicate> getReplicate(String chainTaskId, String walletAddress) {
        Optional<ReplicatesList> optional = getReplicatesList(chainTaskId);
        if (!optional.isPresent()) {
            return Optional.empty();
        }

        for (Replicate replicate : optional.get().getReplicates()) {
            if (replicate.getWalletAddress().equals(walletAddress)) {
                return Optional.of(replicate);
            }
        }

        return Optional.empty();
    }

    public boolean hasWorkerAlreadyParticipated(String chainTaskId, String walletAddress) {
        return getReplicate(chainTaskId, walletAddress).isPresent();
    }

    public int getNbReplicatesWithCurrentStatus(String chainTaskId, ReplicateStatus... listStatus) {
        int nbReplicates = 0;
        for (Replicate replicate : getReplicates(chainTaskId)) {
            for (ReplicateStatus status : listStatus) {
                if (replicate.getCurrentStatus().equals(status)) {
                    nbReplicates++;
                }
            }
        }
        return nbReplicates;
    }

    public int getNbReplicatesContainingStatus(String chainTaskId, ReplicateStatus... listStatus) {
        Set<String> addressReplicates = new HashSet<>();
        for (Replicate replicate : getReplicates(chainTaskId)) {
            List<ReplicateStatus> listReplicateStatus = replicate.getStatusChangeList().stream()
                    .map(ReplicateStatusChange::getStatus)
                    .collect(Collectors.toList());
            for (ReplicateStatus status : listStatus) {
                if (listReplicateStatus.contains(status)) {
                    addressReplicates.add(replicate.getWalletAddress());
                }
            }
        }
        return addressReplicates.size();
    }

    public int getNbOffChainReplicatesWithStatus(String chainTaskId, ReplicateStatus status) {
        return getNbReplicatesWithCurrentStatus(chainTaskId, status) +
                getNbReplicatesWithGivenStatusJustBeforeWorkerLost(chainTaskId, status);
    }

    /*
     * For status = CONTRIBUTED, a replicate will be counted when statuses = { CREATED, ..., CONTRIBUTED, WORKER_LOST }
     * For status = REVEALED, a replicate will be counted when statuses = { CREATED, ..., REVEALED, WORKER_LOST }
     */
    private int getNbReplicatesWithGivenStatusJustBeforeWorkerLost(String chainTaskId, ReplicateStatus status) {
        int nbReplicates = 0;
        for (Replicate replicate : getReplicates(chainTaskId)) {
            int size = replicate.getStatusChangeList().size();
            if (size >= 2 && replicate.getStatusChangeList().get(size - 1).getStatus().equals(WORKER_LOST)
                    && replicate.getStatusChangeList().get(size - 2).getStatus().equals(status)) {
                nbReplicates++;
            }
        }
        return nbReplicates;
    }

    public Optional<Replicate> getRandomReplicateWithRevealStatus(String chainTaskId) {
        List<Replicate> revealReplicates = getReplicates(chainTaskId);
        Collections.shuffle(revealReplicates);

        for (Replicate replicate : revealReplicates) {
            if (replicate.getCurrentStatus().equals(REVEALED)) {
                return Optional.of(replicate);
            }
        }

        return Optional.empty();
    }

    public boolean moreReplicatesNeeded(String chainTaskId, int nbWorkersNeeded, Date timeRef) {
        int nbValidReplicates = 0;
        for (Replicate replicate : getReplicates(chainTaskId)) {
            //TODO think: When do we really need more replicates?
            boolean isReplicateSuccessfullSoFar = ReplicateStatus.getSuccessStatuses().contains(replicate.getCurrentStatus());
            boolean doesContributionTakesTooLong = !replicate.containsContributedStatus() &&
                    replicate.isCreatedMoreThanNPeriodsAgo(2, timeRef);

            if (isReplicateSuccessfullSoFar && !doesContributionTakesTooLong) {
                nbValidReplicates++;
            }
        }
        return nbValidReplicates < nbWorkersNeeded;
    }

    public void updateReplicateStatus(String chainTaskId,
                                      String walletAddress,
                                      ReplicateStatus newStatus,
                                      ReplicateStatusModifier modifier) {
        updateReplicateStatus(chainTaskId, walletAddress, newStatus, modifier, null);
    }

    // in case the task has been modified between reading and writing it, it is retried up to 100 times
    @Retryable(value = {OptimisticLockingFailureException.class}, maxAttempts = 100)
    public void updateReplicateStatus(String chainTaskId,
                                      String walletAddress,
                                      ReplicateStatus newStatus,
                                      ReplicateStatusModifier modifier,
                                      ChainReceipt chainReceipt) {

        long receiptBlockNumber = chainReceipt != null ? chainReceipt.getBlockNumber() : 0;

        Optional<ReplicatesList> optionalReplicates = getReplicatesList(chainTaskId);
        if (!optionalReplicates.isPresent()) {
            log.warn("No replicate found for this chainTaskId for status update [chainTaskId:{}, walletAddress:{}, status:{}]",
                    chainTaskId, walletAddress, newStatus);
            return;
        }

        Optional<Replicate> optionalReplicate = optionalReplicates.get().getReplicateOfWorker(walletAddress);
        if (!optionalReplicate.isPresent()) {
            log.warn("No replicate found for status update [chainTaskId:{}, walletAddress:{}, status:{}]", chainTaskId, walletAddress, newStatus);
            return;
        }

        Replicate replicate = optionalReplicate.get();
        ReplicateStatus currentStatus = replicate.getCurrentStatus();

        // check if it is a valid transition in case the modifier is the worker
        if (modifier.equals(ReplicateStatusModifier.WORKER) &&
                !ReplicateWorkflow.getInstance().isValidTransition(currentStatus, newStatus)) {
            log.error("UpdateReplicateStatus failed (bad workflow transition) [chainTaskId:{}, walletAddress:{}, " +
                            "currentStatus:{}, newStatus:{}]",
                    chainTaskId, walletAddress, currentStatus, newStatus);
            return;
        }

        if (isSuccessBlockchainStatus(newStatus)) {
            replicate = getOnChainRefreshedReplicate(replicate, getChainStatus(newStatus), receiptBlockNumber);

            if (modifier.equals(ReplicateStatusModifier.POOL_MANAGER)) {
                log.warn("Replicate status set by the pool manager [chainTaskId:{}, walletAddress:{}, newStatus:{}, blockNumber:{}]",
                        chainTaskId, walletAddress, newStatus, receiptBlockNumber);
            }

            if (replicate == null) {
                log.error("Failed to refresh replicate with onchain values [chainTaskId:{}, walletAddress:{}, " +
                                "currentStatus:{}, newStatus:{}]",
                        chainTaskId, walletAddress, currentStatus, newStatus);
                return;
            }
        }

        // check that CONTRIBUTE_FAIL and REVEAL_FAIL are correct on-chain
        if (isFailedBlockchainStatus(newStatus) &&
                !isTaskStatusFailOnChain(replicate.getChainTaskId(), replicate.getWalletAddress(), receiptBlockNumber)) {
            return;
        }

        // don't save receipt to db if no relevant info
        if (chainReceipt != null && chainReceipt.getBlockNumber() == 0 && chainReceipt.getTxHash() == null) {
            chainReceipt = null;
        }

        replicate.updateStatus(newStatus, modifier, chainReceipt);
        replicatesRepository.save(optionalReplicates.get());

        // if replicate is not busy anymore, it can notify it
        if (!replicate.isBusyComputing()) {
            applicationEventPublisher.publishEvent(new ReplicateComputedEvent(replicate));
        }

        log.info("UpdateReplicateStatus succeeded [chainTaskId:{}, walletAddress:{}, currentStatus:{}, newStatus:{}, modifier:{}]", chainTaskId,
                walletAddress, currentStatus, newStatus, modifier);
        applicationEventPublisher.publishEvent(new ReplicateUpdatedEvent(replicate.getChainTaskId(), newStatus));
    }

    @Recover
    public void updateReplicateStatus(OptimisticLockingFailureException exception,
                                      String chainTaskId,
                                      String walletAddress,
                                      ReplicateStatus newStatus,
                                      long blockNumber,
                                      ReplicateStatusModifier modifier) {
        log.error("Maximum number of tries reached [exception:{}]", exception.getMessage());
        exception.printStackTrace();
    }

    private boolean isSuccessBlockchainStatus(ReplicateStatus newStatus) {
        return getChainStatus(newStatus) != null;
    }

    private boolean isFailedBlockchainStatus(ReplicateStatus status) {
        return Arrays.asList(CONTRIBUTE_FAILED, REVEAL_FAILED).contains(status);
    }

    private Replicate getOnChainRefreshedReplicate(Replicate replicate, ChainContributionStatus wishedChainStatus, long blockNumber) {
        // check that the blockNumber is already available for the scheduler
        if (!web3jService.isBlockNumberAvailable(blockNumber)) {
            log.error("This block number is not available, even after waiting for some time [blockNumber:{}]", blockNumber);
            return null;
        }

        boolean isWishedStatusProvedOnChain = iexecHubService.doesWishedStatusMatchesOnChainStatus(replicate.getChainTaskId(), replicate.getWalletAddress(), wishedChainStatus);
        if (isWishedStatusProvedOnChain) {
            return getReplicateWithBlockchainUpdates(replicate, wishedChainStatus);
        } else {
            log.error("Onchain status is different from wishedChainStatus (should wait?) [chainTaskId:{}, worker:{}, " +
                    "blockNumber:{}, wishedChainStatus:{}]", replicate.getChainTaskId(), replicate.getWalletAddress(), blockNumber, wishedChainStatus);
        }

        return null;
    }

    private Replicate getReplicateWithBlockchainUpdates(Replicate replicate, ChainContributionStatus wishedChainStatus) {
        Optional<ChainContribution> optional = iexecHubService.getContribution(replicate.getChainTaskId(), replicate.getWalletAddress());
        if (!optional.isPresent()) {
            return null;
        }

        ChainContribution chainContribution = optional.get();
        if (wishedChainStatus.equals(ChainContributionStatus.CONTRIBUTED)) {
            replicate.setContributionHash(chainContribution.getResultHash());
        }
        return replicate;
    }

    private boolean isTaskStatusFailOnChain(String chainTaskId, String walletAddress, long blockNumber) {
        if (!web3jService.isBlockNumberAvailable(blockNumber)) {
            log.error("This block number is not available, even after waiting for some time [blockNumber:{}]", blockNumber);
            return false;
        }

        Optional<ChainContribution> optional = iexecHubService.getContribution(chainTaskId, walletAddress);
        if (!optional.isPresent()) {
            return false;
        }

        ChainContribution contribution = optional.get();
        ChainContributionStatus chainStatus = contribution.getStatus();
        if (!chainStatus.equals(ChainContributionStatus.CONTRIBUTED) && !chainStatus.equals(ChainContributionStatus.REVEALED)) {
            return true;
        } else {
            log.warn("The onchain status of the contribution is not a failed one " +
                    "[chainTaskId:{}, wallet:{}, blockNumber:{}, onChainStatus:{}]", chainStatus, walletAddress, blockNumber, chainStatus);
            return false;
        }
    }

}
