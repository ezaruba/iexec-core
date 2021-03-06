package com.iexec.core.result;

import com.iexec.common.chain.ChainDeal;
import com.iexec.common.chain.ChainTask;
import com.iexec.common.utils.BytesUtils;
import com.iexec.core.chain.IexecHubService;
import com.mongodb.client.gridfs.model.GridFSFile;

import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.data.mongodb.gridfs.GridFsOperations;
import org.springframework.data.mongodb.gridfs.GridFsResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import static org.assertj.core.api.Java6Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;

public class ResultServiceTest {

    @Mock
    private IexecHubService iexecHubService;

    @Mock
    GridFsOperations gridFsOperations;

    @InjectMocks
    private ResultService resultService;

    private Integer chainId;
    private String chainDealId;
    private String chainTaskId;
    private String resultFilename;
    private String walletAddress;
    private byte[] zip;

    @Before
    public void init() {
        MockitoAnnotations.initMocks(this);
        chainId = 17;
        chainDealId = "Oxdea1";
        chainTaskId = "0x1";
        resultFilename = "iexec-result-" + chainTaskId;
        walletAddress = "0x123abc";
        zip = new byte[10];
    }

    @Test
    public void isNotAbleToUploadSinceResultAlreadyExists() {
        GridFSFile gridFSFileMock = Mockito.mock(GridFSFile.class);

        when(gridFsOperations.findOne(any())).thenReturn(gridFSFileMock);

        assertThat(resultService.canUploadResult(chainTaskId, walletAddress, zip)).isFalse();
    }

    @Test
    public void isNotAbleToUploadSinceChainStatusIsNotRevealed() {
        when(gridFsOperations.findOne(any())).thenReturn(null);
        when(iexecHubService.doesWishedStatusMatchesOnChainStatus(any(), any(), any())).thenReturn(false);

        assertThat(resultService.canUploadResult(chainTaskId, walletAddress, zip)).isFalse();
    }

    @Test
    public void isAbleToUpload() {
        when(gridFsOperations.findOne(any())).thenReturn(null);
        when(iexecHubService.doesWishedStatusMatchesOnChainStatus(any(), any(), any())).thenReturn(true);

        assertThat(resultService.canUploadResult(chainTaskId, walletAddress, zip)).isTrue();
    }

    @Test
    public void shouldNotFindResultInDatabase() {
        when(gridFsOperations.findOne(any())).thenReturn(null);

        assertThat(resultService.isResultInDatabase(chainTaskId)).isFalse();
    }

    @Test
    public void shouldFindResultInDatabase() {
        GridFSFile gridFSFileMock = Mockito.mock(GridFSFile.class);

        when(gridFsOperations.findOne(any())).thenReturn(gridFSFileMock);

        assertThat(resultService.isResultInDatabase(chainTaskId)).isTrue();
    }

    @Test
    public void shouldNotAddResultSinceResultIsNull() {
        String data = "data";
        byte[] dataBytes = data.getBytes();

         String filename = resultService.addResult(null, dataBytes);

         assertThat(filename).isEmpty();
        Mockito.verify(gridFsOperations, Mockito.times(0))
                .store(any(InputStream.class), Mockito.eq(filename), any(Result.class));
    }

    @Test
    public void shouldNotAddResultSinceChainTaskIdIsNull() {
        Result result = Result.builder().build();

        String data = "data";
        byte[] dataBytes = data.getBytes();

         String filename = resultService.addResult(result, dataBytes);

         assertThat(filename).isEmpty();
        Mockito.verify(gridFsOperations, Mockito.times(0))
                .store(any(InputStream.class), Mockito.eq(filename), Mockito.eq(result));
    }

    @Test
    public void shouldAddResult() {
        Result result = Result.builder().chainTaskId(chainTaskId).build();
        String data = "data";
        byte[] dataBytes = data.getBytes();

        String filename = resultService.addResult(result, dataBytes);

        assertThat(filename).isEqualTo(resultFilename);
        Mockito.verify(gridFsOperations, Mockito.times(1))
            .store(any(InputStream.class), Mockito.eq(filename), Mockito.eq(result));
    }

    @Test
    public void shouldGetResultByChainTaskId() throws IOException {
        GridFsResource resource = Mockito.mock(GridFsResource.class);
        InputStream inputStream = IOUtils.toInputStream("stream", "UTF-8");
        byte[] inputStreamBytes = "stream".getBytes();

         when(gridFsOperations.getResources(resultFilename))
            .thenReturn(new GridFsResource[] {resource});
        when(resource.getInputStream()).thenReturn(inputStream);

         byte[] result = resultService.getResultByChainTaskId(chainTaskId);
        assertThat(result).isEqualTo(inputStreamBytes);
    }

    @Test
    public void shouldGetEmptyArraySinceNoResultWithChainTaskId() throws IOException {
        when(gridFsOperations.getResources(resultFilename)).thenReturn(new GridFsResource[0]);

         byte[] result = resultService.getResultByChainTaskId(chainTaskId);
        assertThat(result).isEmpty();
    }

    @Test
    public void isNotAuthorizedToGetResultSinceWalletAddressDifferentFromRequester() {
        String requester = "0xa";
        String beneficiary = BytesUtils.EMPTY_ADDRESS;
        when(iexecHubService.getChainTask("0x1")).thenReturn(Optional.of(ChainTask.builder().dealid(chainDealId).build()));
        when(iexecHubService.getChainDeal(chainDealId)).thenReturn(Optional.of(ChainDeal.builder().requester(requester).beneficiary(beneficiary).build()));
        assertThat(resultService.canGetResult(chainId, chainTaskId, "0xabcd1339Ec7e762e639f4887E2bFe5EE8023E23E")).isFalse();
    }

    @Test
    public void isNotAuthorizedToGetResultSinceCannotGetChainTask() {
        when(iexecHubService.getChainTask("0x1")).thenReturn(Optional.empty());

        assertThat(resultService.canGetResult(chainId, chainTaskId, "0xabcd1339Ec7e762e639f4887E2bFe5EE8023E23E")).isFalse();
    }

    @Test
    public void isNotAuthorizedToGetResultSinceCannotGetChainDeal() {
        when(iexecHubService.getChainTask("0x1")).thenReturn(Optional.of(ChainTask.builder().dealid(chainDealId).build()));
        when(iexecHubService.getChainDeal(chainDealId)).thenReturn(Optional.empty());

        assertThat(resultService.canGetResult(chainId, chainTaskId, "0xabcd1339Ec7e762e639f4887E2bFe5EE8023E23E")).isFalse();
    }

    @Test
    public void isNotAuthorizedToGetResultSinceWalletAddressDifferentFromBeneficiary() {
        String requester = "0xa";
        String beneficiary = "0xb";
        when(iexecHubService.getChainTask("0x1")).thenReturn(Optional.of(ChainTask.builder().dealid(chainDealId).build()));
        when(iexecHubService.getChainDeal(chainDealId)).thenReturn(Optional.of(ChainDeal.builder().requester(requester).beneficiary(beneficiary).build()));
        assertThat(resultService.canGetResult(chainId, chainTaskId, "0xabcd1339Ec7e762e639f4887E2bFe5EE8023E23E")).isFalse();
    }

    @Test
    public void isNotAuthorizedToGetResultSinceWalletAddressShouldBeBeneficiaryWhenSet() {
        String requester = "0xabcd1339Ec7e762e639f4887E2bFe5EE8023E23E";
        String beneficiary = "0xb";
        when(iexecHubService.getChainTask("0x1")).thenReturn(Optional.of(ChainTask.builder().dealid(chainDealId).build()));
        when(iexecHubService.getChainDeal(chainDealId)).thenReturn(Optional.of(ChainDeal.builder().requester(requester).beneficiary(beneficiary).build()));
        assertThat(resultService.canGetResult(chainId, chainTaskId,"0xabcd1339Ec7e762e639f4887E2bFe5EE8023E23E")).isFalse();
    }

    @Test
    public void isAuthorizedToGetResult() {
        String requester = "0xabcd1339Ec7e762e639f4887E2bFe5EE8023E23E";
        String beneficiary = BytesUtils.EMPTY_ADDRESS;
        when(iexecHubService.getChainTask(chainTaskId)).thenReturn(Optional.of(ChainTask.builder().dealid(chainDealId).build()));
        when(iexecHubService.getChainDeal(chainDealId)).thenReturn(Optional.of(ChainDeal.builder().requester(requester).beneficiary(beneficiary).build()));
        assertThat(resultService.canGetResult(chainId, chainTaskId, "0xabcd1339Ec7e762e639f4887E2bFe5EE8023E23E")).isTrue();
    }
}