# Budget Race Condition Fix

## Problem Identified

The bidder was experiencing frequent "out of budget" errors despite having sufficient remaining budget. This was caused by a **race condition** in the budget checking logic.

### Root Cause

1. **Budget check happened BEFORE placing bids** (non-atomically)
2. **Budget deduction only happened AFTER winning** auctions
3. **Multiple concurrent requests** could all pass the budget check simultaneously
4. All these requests would place bids, even though collectively they exceeded available budget

### Example Scenario

```
Time 0: Creative has $25 budget
Time 1: 100 concurrent bid requests arrive
Time 2: All 100 check budget → all see $25 > 0 ✓
Time 3: All 100 bids placed (but total cost would exceed budget!)
Time 4: Some bids rejected by SSP as "out of budget"
Time 5: Only actual wins deduct budget
```

## Solution Implemented

**Optimistic Budget Reservation** - Reserve budget atomically when placing a bid, then refund on loss.

### Changes Made

#### 1. BidderStatsCache.java
Added three new Redis Lua scripts for atomic operations:

- **`RESERVE_BUDGET_SCRIPT`**: Atomically checks and decrements budget
  - Returns remaining budget if successful
  - Returns -1 if insufficient budget
  
- **`REFUND_BUDGET_SCRIPT`**: Atomically adds budget back

New methods:
- `reserveBudget(creativeId, bidPrice)`: Reserve budget when placing bid
- `refundBudget(creativeId, amount)`: Refund when losing or overpaying

#### 2. BiddingService.java
Optimized for <100ms latency requirement:

**Two-phase approach:**
1. **Fast batch pre-filter** (1 MGET Redis call): Filter creatives with budget >= bid price
2. **Random selection + atomic reservation** (1 Redis call): Pick one randomly, reserve atomically

**Flow:**
- Batch check all matching creatives' budgets (single MGET)
- Filter to those with sufficient budget
- Randomly select ONE creative (ensures fairness)
- Atomically reserve budget for that creative
- If reservation fails (race): fail fast with "budget_race_condition"

**Total: 2 Redis calls** (same as before, but now atomic)

#### 3. AuctionNoticeConsumer.java
Updated auction notice handling:

**On WIN**:
- Budget already reserved at bid time
- If clearing price < bid price: refund the difference
- If clearing price > bid price: deduct extra (shouldn't happen in second-price)

**On LOSS**:
- Refund the entire reserved bid amount
- Budget becomes available for other auctions immediately

## Benefits

1. **Eliminates race conditions**: Atomic reserve prevents over-bidding
2. **Fair creative selection**: Random selection ensures all creatives get equal chance
3. **Accurate budget tracking**: Budget reflects committed bids, not just wins
4. **Fast refunds on loss**: Budget becomes available immediately for reuse
5. **Low latency**: Only 2 Redis calls total (same as before), no sequential retries

## Trade-offs

- **Same Redis calls**: 2 calls per bid (1 MGET pre-filter + 1 atomic reserve)
- **Fail fast on race**: If reservation fails, returns "budget_race_condition" instead of trying other creatives
  - Keeps latency low (<100ms requirement)
  - Race is rare after pre-filter
  - Can be monitored via metrics

## Testing Recommendations

1. Load test with high concurrency to verify no over-bidding
2. Monitor Redis latency under load
3. Verify budget refunds happen correctly on losses
4. Check that all matching creatives get selected fairly over time
