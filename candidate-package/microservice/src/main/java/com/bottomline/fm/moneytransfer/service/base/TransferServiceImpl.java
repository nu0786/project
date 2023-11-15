package com.bottomline.fm.moneytransfer.service.base;

import com.bottomline.fm.moneytransfer.exception.BadRequestException;
import com.bottomline.fm.moneytransfer.model.Account;
import com.bottomline.fm.moneytransfer.model.Transfer;
import com.bottomline.fm.moneytransfer.repository.spi.TransferRepositoryService;
import com.bottomline.fm.moneytransfer.service.spi.AccountService;
import com.bottomline.fm.moneytransfer.service.spi.TransferService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service("transferService")
public class TransferServiceImpl implements TransferService {
    private final AccountService accountService;
    private final TransferRepositoryService transferRepositoryService;
    private final Map<String, Integer> stats;
    private final TransactionTemplate transactionTemplate;

    public TransferServiceImpl(@Qualifier("accountService")AccountService accountService,
                               @Qualifier("transferRepositoryService")TransferRepositoryService transferRepositoryService,
                               PlatformTransactionManager platformTransactionManager) {
        this.accountService = accountService;
        this.transferRepositoryService = transferRepositoryService;
        stats = new HashMap<>();
        transactionTemplate = new TransactionTemplate(platformTransactionManager);
    }

    @Override
    public Optional<Transfer> performTransferWithoutApproval(String fromAccountNumber, String toAccountNumber, String currency, BigDecimal amount) {
        ExecutorService executor = Executors.newFixedThreadPool(8); // Create a thread pool with 8 threads for parallel execution
        List<Future<Optional<Transfer>>> futures = new ArrayList<>();

        for (int i = 0; i < 8; i++) { // Submit the task to the thread pool
            Future<Optional<Transfer>> future = executor.submit(() -> {
                return transactionTemplate.execute((s) -> {
                    Account fromAccount = accountService.findAccountByNumber(fromAccountNumber);
                    Account toAccount = accountService.findAccountByNumber(toAccountNumber);
                    if (!fromAccount.getCurrency().equals(toAccount.getCurrency())) {
                        throw new BadRequestException(String.format("Cannot perform transfer between those two accounts (from: %s, to: %s) because they are not in same currency", fromAccountNumber, toAccountNumber));
                    }
                    if (!fromAccount.getCurrency().equals(currency)) {
                        throw new BadRequestException(String.format("Cannot perform transfer between those two accounts (from: %s, to: %s) because currency involved(%s) is different than accounts currency(%s)", fromAccountNumber, toAccountNumber, currency, fromAccount.getCurrency()));
                    }
                    if (amount.compareTo(BigDecimal.ZERO) < 0) {
                        throw new BadRequestException(String.format("Cannot perform transfer between those two accounts (from: %s, to: %s) because amount is negative. Invert from and to account and use a positive amount instead", fromAccountNumber, toAccountNumber));
                    }
                    if (fromAccount.getBalance().subtract(amount).compareTo(BigDecimal.ZERO) < 0) {
                        return Optional.empty();
                    }
                    stats.putIfAbsent(fromAccountNumber, 0);
                    stats.put(fromAccountNumber, stats.get(fromAccountNumber) + 1);
                    fromAccount.setBalance(fromAccount.getBalance().subtract(amount));
                    toAccount.setBalance(toAccount.getBalance().add(amount));
                    accountService.updateAccount(fromAccount);
                    accountService.updateAccount(toAccount);
                    return Optional.of(transferRepositoryService.create(new Transfer(Instant.now(), fromAccount, toAccount, amount, currency)));
                });
            });
            futures.add(future);
        }

        // Wait for all tasks to complete
        for (Future<Optional<Transfer>> future : futures) {
            try {
                Optional<Transfer> result = future.get();
                if (result.isPresent()) {
                    // Transaction is completed
                }
            } catch (InterruptedException | ExecutionException e) {
                // Exception Occurred during transaction
            }
        }

        executor.shutdown(); // Shutdown the executor after all tasks are completed
        return Optional.empty();
    }

    @Override
    public Integer numberOfTransfersForAccount(String fromAccountNumber) {
        return stats.getOrDefault(fromAccountNumber, 0);
    }

    @Override
    public Optional<Transfer> findById(Long id) {
        return transferRepositoryService.findById(id);
    }

    @Override
    public List<String> topSenderAccounts() {
        return transferRepositoryService.topSenderAccounts();
    }
}
