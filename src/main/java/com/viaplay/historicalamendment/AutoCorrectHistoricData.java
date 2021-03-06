package com.viaplay.historicalamendment;

import com.amazonaws.services.dynamodbv2.model.*;
import com.viaplay.historicalamendment.model.RecordDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import javax.annotation.PostConstruct;
import java.io.*;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class AutoCorrectHistoricData {
    private static final String CSV_PATH = "/Users/soorjahr/Documents/historical-amendment/src/main/resources/step2_rough.csv"; // update

    private Table upmInProgressTable = null;
    private Table upmWatched = null;

    @Autowired
    private List<RecordProcessor> recordProcessors;

    @Autowired
    @Qualifier("spRecordProcessor")
    private SpRecordProcessor spRecordProcessor;

    @Autowired
    private AmazonDynamoDB client;

    private AtomicInteger count = new AtomicInteger(0);

    private File csvOutputFile;
    private PrintWriter pw;

    public String convertToCSV(String[] data) {
        return java.util.stream.Stream.of(data).
                collect((Collectors.joining(",")));

    }

    @PostConstruct
    public void init() throws FileNotFoundException {
        String path = "/Users/soorjahr/Documents/historical-amendment/src/main/resources/failed_records.csv";
        csvOutputFile = new File(path); //TODO put into application param
        pw = new PrintWriter(csvOutputFile);
    }

    @EventListener(ApplicationReadyEvent.class)
    public <T> void autoCorrectData() throws FileNotFoundException {
        Instant start = Instant.now();
        String line = "";
        String cvsSplitBy = ",";
        BufferedReader br = new BufferedReader(new FileReader(CSV_PATH));

        try (br) {
            int count = 0;
            while ((line = br.readLine()) != null) {
                // CSV Header : userId,profileId,productGuid,programTittle,position,duration,completedPercentage,nextEpisodeGuid

                // use comma as separator
                String userId = "";
                String profileId = "";
                String newPrimaryKey = "";
                String[] items = line.split(cvsSplitBy);
                userId = items[0];
                profileId = items[1];
                String programGuid = items[2];
                String seriesGuid = items[3];
                boolean isKids = Boolean.getBoolean(items[7]);
                System.out.println("->>>>>>>>>>>>>>>> user ID raw : " + userId);
                RecordDao recordDaoBuilder = RecordDao.builder().userId(userId).
                        profileId(profileId).programGuid(programGuid).seriesGuid(seriesGuid).isKids(isKids).build();

                if (!spRecordProcessor.shouldUpdateRecord(recordDaoBuilder)) {
                    System.out.println("record skipped : " + userId);
                    continue;
                }

                List<CompletableFuture<T>> futureQuery = new ArrayList<>();
                for (RecordProcessor recordProcessor : recordProcessors) {
                    futureQuery.add(recordProcessor.createUpdateQuery(recordDaoBuilder));

                }
                // Create a combined Future using allOf()
                //TODO handle child level exceptions
                String finalLine = line;
                CompletableFuture.allOf(
                        futureQuery.toArray(new CompletableFuture[futureQuery.size()])
                ).thenAccept(ignored -> {
                    List<TransactWriteItem> genericActions = futureQuery.stream().
                            map(query -> query.join())
                            .map(resolvedQuery -> {
                                if (resolvedQuery instanceof Update) {
                                    return new TransactWriteItem().withUpdate((Update) resolvedQuery);
                                } else if (resolvedQuery instanceof Put) {
                                    return new TransactWriteItem().withPut((Put) resolvedQuery);
                                }
                                if (resolvedQuery instanceof Delete) {
                                    return new TransactWriteItem().withDelete((Delete) resolvedQuery);
                                }
                                return null;
                            }).
                                    filter(e -> e != null).
                                    collect(Collectors.toList());

                    System.out.println(" transaction size : " + genericActions.size());

                    runInTranscation(genericActions, finalLine);

                }).exceptionally(exception -> {
                    System.out.println("in exceptionally");
                    System.err.println(exception);
                    return null;
                });


            }
            Instant finish = Instant.now();
            System.out.println(" operation took: " + Duration.between(start, finish).toMinutes() + " to complete");
            System.out.println("All Done!!!!!!!!!");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void runInTranscation(Collection<TransactWriteItem> actions, String line) {
        if (actions == null || actions.isEmpty()) {
            return;
        }

        System.out.println("proceeding with transaction : " + actions);
        TransactWriteItemsRequest transactionRequest = new TransactWriteItemsRequest()
                .withTransactItems(actions)
                .withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL);

        // Run the transaction and process the result.
        try {
            client.transactWriteItems(transactionRequest);
            System.out.println("Transaction Successful : Count tally of corrected data is: " + count.incrementAndGet());

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("One of the table involved in the transaction is not found: " + e.getMessage());
            // CSV Header : userId,profileId,productGuid,programTittle,position,duration,completedPercentage,nextEpisodeGuid
            writeToErrorQueue(line);

        }


    }

    private void writeToErrorQueue(String line) {
        try {
            pw.println(line);
        } catch (Exception ex) {
            System.out.println(" unable to write in into the CSV file " + ex.getMessage());
        } finally {
            pw.close();
        }
    }


}
