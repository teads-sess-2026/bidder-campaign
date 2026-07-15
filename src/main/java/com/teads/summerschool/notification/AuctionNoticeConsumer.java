package com.teads.summerschool.notification;

import com.teads.summerschool.config.BidderProperties;
import com.teads.summerschool.metrics.BidderMetrics;
import com.teads.summerschool.proto.AuctionNoticeProto;
import com.teads.summerschool.record.BidderStatsCache;
import com.teads.summerschool.record.OwnBidCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class AuctionNoticeConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuctionNoticeConsumer.class);

    private final WinNoticeRepository winNoticeRepository;
    private final BidderProperties properties;
    private final BidderStatsCache statsCache;
    private final BidderMetrics metrics;
    private final OwnBidCache ownBidCache;

    public AuctionNoticeConsumer(WinNoticeRepository winNoticeRepository,
                                 BidderProperties properties,
                                 BidderStatsCache statsCache,
                                 BidderMetrics metrics,
                                 OwnBidCache ownBidCache) {
        this.winNoticeRepository = winNoticeRepository;
        this.properties = properties;
        this.statsCache = statsCache;
        this.metrics = metrics;
        this.ownBidCache = ownBidCache;
    }

    @KafkaListener(topics = "${kafka.topic.auction-notifications}",
            autoStartup = "${spring.kafka.listener.auto-startup:true}")
    public void consume(byte[] message) {
        try{
            AuctionNoticeProto.AuctionNotice notice=AuctionNoticeProto.AuctionNotice.parseFrom(message);
            boolean won = properties.getId().equals(notice.getWinningBidderId());
            OwnBidCache.Entry ourBid=ownBidCache.get(notice.getRequestId());

            log.info("KAFKA id={} winner={} won={}", notice.getRequestId(), notice.getWinningBidderId(), won);
            if(won){
                if(ourBid==null){
                    log.warn("WIN but no matching bid record found for request_id={} - skipping budget update",
                        notice.getRequestId());
                    return;
                }

                statsCache.recordWin(ourBid.creativeId(), notice.getClearingPrice()).subscribe();
                metrics.recordWin(notice.getClearingPrice());

                WinNotice winNotice = new WinNotice(
                    notice.getRequestId(),
                    properties.getId(),
                    notice.getClearingPrice(),
                    ourBid.bidPrice()
                );


                winNoticeRepository.save(winNotice)
                    .doOnSuccess(saved -> log.info("WIN id={} creative={} clearing={} bid={} saved",
                        notice.getRequestId(), ourBid.creativeId(), notice.getClearingPrice(), ourBid.bidPrice()))
                    .doOnError(error -> log.error("Failed to save win notice for id={}: {}",
                        notice.getRequestId(), error.getMessage()))
                    .subscribe();
            }else{
                if(ourBid!=null){
                    metrics.recordLoss();
                    double gap = notice.getClearingPrice() - ourBid.bidPrice();
                    log.info("LOSS id={} creative={} ourBid={} clearing={} gap={} winner={}",
                        notice.getRequestId(), ourBid.creativeId(), ourBid.bidPrice(),
                        notice.getClearingPrice(), gap, notice.getWinningBidderId());
                }
            }
        }catch (Exception e){
            log.error("** KAFKA ERROR failed to process auction notice: {}", e.getMessage());
        }
    }
}
