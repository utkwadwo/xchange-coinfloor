/*
 * Created on Feb 6, 2017
 */
package uk.co.coinfloor.xchange;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.codec.binary.Base64;
import org.knowm.xchange.BaseExchange;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.Order.OrderStatus;
import org.knowm.xchange.dto.Order.OrderType;
import org.knowm.xchange.dto.marketdata.OrderBook;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.dto.marketdata.Ticker.Builder;
import org.knowm.xchange.dto.marketdata.Trades;
import org.knowm.xchange.dto.marketdata.Trades.TradeSortType;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.dto.trade.MarketOrder;
import org.knowm.xchange.dto.trade.OpenOrders;
import org.knowm.xchange.dto.trade.UserTrade;
import org.knowm.xchange.dto.trade.UserTrades;
import org.knowm.xchange.exceptions.ExchangeException;
import org.knowm.xchange.exceptions.NotAvailableFromExchangeException;
import org.knowm.xchange.service.marketdata.MarketDataService;
import org.knowm.xchange.service.trade.TradeService;
import org.knowm.xchange.service.trade.params.TradeHistoryParamCurrencyPair;
import org.knowm.xchange.service.trade.params.TradeHistoryParamPaging;
import org.knowm.xchange.service.trade.params.TradeHistoryParams;
import org.knowm.xchange.service.trade.params.orders.OpenOrdersParams;

import uk.co.coinfloor.api.Coinfloor;
import uk.co.coinfloor.api.Coinfloor.OrderInfo;
import uk.co.coinfloor.api.Coinfloor.TickerInfo;
import uk.co.coinfloor.api.CoinfloorException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import si.mazi.rescu.SynchronizedValueFactory;

public class CoinfloorExchange extends BaseExchange {

	public static class CoinfloorTradeHistoryParams implements TradeHistoryParamCurrencyPair, TradeHistoryParamPaging {

		public static final Integer defaultPageLength = Integer.valueOf(100);

		CurrencyPair pair;
		Integer pageLength = defaultPageLength, pageNumber;

		@Override
		public void setCurrencyPair(CurrencyPair pair) {
			this.pair = pair;
		}

		@Override
		public CurrencyPair getCurrencyPair() {
			return pair;
		}

		@Override
		public void setPageLength(Integer pageLength) {
			this.pageLength = pageLength;
		}

		@Override
		public Integer getPageLength() {
			return pageLength;
		}

		@Override
		public void setPageNumber(Integer pageNumber) {
			this.pageNumber = pageNumber;
		}

		@Override
		public Integer getPageNumber() {
			return pageNumber;
		}

	}

	private static class BookData {

		final LinkedHashMap<Long, LimitOrder> orders = new LinkedHashMap<>();

		volatile boolean watchingOrders;
		volatile Ticker cachedTicker;

		private transient OrderBook cachedOrderBook;

		BookData() {
		}
/* If the orderbook is empty, "==null", then we create an arraylist and then use a for each loop to iterate over orders.values output. We 
use a ternerary conditional, if the ordter.getType returns orderType.BID then we return "bids" otherwise we return "asks" and then 
with the dot notation add this output to the arraylist called bids (or ask, respectively) with the bids.add(order) we add that specific
order that we are on given the loop. We will then end up with an array list with all bid orders and another with all ask orders. Then 
we use the Collections class to sort the bids array list, "Collections.sort(bids)" and another to sort the ask array list 
"Collections.sort(asks) . Then we will have a sorted list of bids and asks from the OrderBook. The function getOrdderBook then returns 
a type of OrderBook class called CachedOrderbook and it contains a sorted list of asks and bids*/
		synchronized OrderBook getOrderBook() {
			if (cachedOrderBook == null) {
				ArrayList<LimitOrder> bids = new ArrayList<>(), asks = new ArrayList<>();
				for (LimitOrder order : orders.values()) {
					(order.getType() == OrderType.BID ? bids : asks).add(order);
				}
				Collections.sort(bids);
				Collections.sort(asks);
				cachedOrderBook = new OrderBook(null, asks, bids);
			}
			return cachedOrderBook;
		}

		synchronized void orderOpened(Long id, LimitOrder order) {
			orders.put(id, order);
			cachedOrderBook = null;
		}

		/* orderChanged function returns a type of LimitOrder. First we get the order based on id and set the order, which
		is a linked listed of id and order, to orders.get(id). 
		
		if there is an order with that id, that is the get function does not return "null", then set/update the order status
		
		if the newQuantity is 0 then we return OrderStatus.FILLED, otherwise we return OrderStatus.PARTIALLY_FILLED
		, and thn set the order status accordingly with the "order.setOrderStatus(OrderStatus.FILLED)" 
		or order.setOrderStatus(OrderStatus.PARTIALLY_FILLED)
		
		then in the orders linked list we use the put function to put in the id passed to the orderChanged function an the
		order, which is equal to the same order id but now with a new quantity (also passed to the function))
		
		So, this essentially just checks the status of an order based on id and then updates the status to Filled or partially filled
		based on whether the new quantity of the order is 0 or not
		*/
		synchronized LimitOrder orderChanged(Long id, long newQuantity) {
			LimitOrder order = orders.get(id);
			if (order != null) {
				order.setOrderStatus(newQuantity == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED);
				orders.put(id, order = makeLimitOrder(order, newQuantity));
				cachedOrderBook = null;
			}
			return order;
		}

		/* orderClosed does not return anything. id is passed to function and function removes that order from the order linked list. 
		
		the orders.remove(id) returns the id and quantity for the specific order at id  and sets the variable "order" to that value. 
		
		then if the order is not empty/that is the order exists, then will check if the order.getStatus is not filled,
		if the ordre is not filled, then we set the status of the order to Canceled with the order.setOrderSstatus(orderStatus.CANCELED)
		
		The logic is that when we call the orderClosed function, if the order does exist (does not return a null from the orders.remove(id))
		then we know the order exists. if the order exists then we need to determine whether the order is filled. if the order is filled
		then we can leave the status as filled, if the order is not filled, then since we called the orderClosed function, we want to close
		the order and so wewill change the status of the order to cancelled. 
		
		Then we set the CahcedorderBook to null 
		
		***not sure why set the cachedOrderBook to null unless there are no other cached orders***
		 */
		
		synchronized void orderClosed(Long id) {
			LimitOrder order = orders.remove(id);
			if (order != null) {
				if (order.getStatus() != OrderStatus.FILLED) {
					order.setOrderStatus(OrderStatus.CANCELED);
				}
				cachedOrderBook = null;
			}
		}

	}

	public static final String BIST_URL_KEY = "bistURL";
	public static final Currency XBT = Currency.BTC.getCodeCurrency("XBT");

	static final int PRICE_SCALE = 2, XBT_SCALE = 4;

	static final DateFormat dateFormatPrototype = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK);
	static final URL defaultBistURL;

	static {
		try {
			defaultBistURL = new URL("https://webapi.coinfloor.co.uk:8090/bist/");
		}
		catch (MalformedURLException impossible) {
			throw new RuntimeException(impossible);
		}
	}

	static final HashMap<Integer, Currency> codeToCurrency;
	static final HashMap<Currency, Integer> currencyToCode;

	static {
		HashMap<Integer, Currency> map = new HashMap<>((5 + 2) / 3 * 4);
		map.put(0xF800, XBT);
		map.put(0xFA00, Currency.EUR);
		map.put(0xFA20, Currency.GBP);
		map.put(0xFA80, Currency.USD);
		map.put(0xFDA8, Currency.PLN);
		codeToCurrency = map;
	}

	static {
		HashMap<Currency, Integer> map = new HashMap<>((5 + 2) / 3 * 4);
		map.put(XBT, 0xF800);
		map.put(Currency.EUR, 0xFA00);
		map.put(Currency.GBP, 0xFA20);
		map.put(Currency.USD, 0xFA80);
		map.put(Currency.PLN, 0xFDA8);
		currencyToCode = map;
	}

	Coinfloor coinfloor;

	LinkedHashMap<Long, LimitOrder> openOrders;

	private final HashMap<CurrencyPair, BookData> books = new HashMap<>((4 + 2) * 3 / 4);

	@Override
	protected void finalize() throws Throwable {
		disconnect();
	}

	public void disconnect() {
		if (coinfloor != null) {
			coinfloor.disconnect();
		}
	}

	@Override
	public SynchronizedValueFactory<Long> getNonceFactory() {
		throw new UnsupportedOperationException();
	}

	@Override
	public ExchangeSpecification getDefaultExchangeSpecification() {
		ExchangeSpecification spec = new ExchangeSpecification(getClass());
		spec.setExchangeName("Coinfloor");
		spec.setExchangeDescription("The leading XBT:GBP exchange.");
		spec.setHost("api.coinfloor.co.uk");
		spec.setPort(443);
		spec.setSslUri(Coinfloor.defaultURI.toASCIIString());
		spec.setExchangeSpecificParametersItem(BIST_URL_KEY, defaultBistURL);
		return spec;
	}

	@Override
	public void applySpecification(ExchangeSpecification exchangeSpecification) {
		if (coinfloor != null) {
			coinfloor.disconnect();
			coinfloor = null;
			openOrders = null;
			synchronized (books) {
				books.clear();
			}
		}
		super.applySpecification(exchangeSpecification);
	}

	@Override
	public void remoteInit() throws IOException, ExchangeException {
		try {
			connectIfNeeded();
		}
		catch (CoinfloorException e) {
			throw new ExchangeException(e.getMessage(), e);
		}
	}

	@Override
	protected void initServices() {
		marketDataService = new MarketDataService() {

			@Override
			public Ticker getTicker(CurrencyPair currencyPair, Object... args) throws ExchangeException, NotAvailableFromExchangeException, IOException {
				BookData bookData = getOrCreateBookData(currencyPair);
				Ticker ticker = bookData.cachedTicker;
				if (ticker == null) {
					Integer base, counter;
					if ((base = currencyToCode.get(currencyPair.base)) == null || (counter = currencyToCode.get(currencyPair.counter)) == null) {
						throw new NotAvailableFromExchangeException();
					}
					try {
						connectIfNeeded();
						TickerInfo info = coinfloor.watchTicker(base, counter, true);
						bookData.cachedTicker = ticker = makeTicker(currencyPair, info.last, info.bid, info.ask, info.low, info.high, info.volume);
					}
					catch (CoinfloorException e) {
						throw new ExchangeException(e.getMessage(), e);
					}
				}
				return ticker;
			}

			@Override
			public OrderBook getOrderBook(CurrencyPair currencyPair, Object... args) throws ExchangeException, NotAvailableFromExchangeException, IOException {
				BookData bookData = getOrCreateBookData(currencyPair);
				if (!bookData.watchingOrders) {
					Integer base, counter;
					if ((base = currencyToCode.get(currencyPair.base)) == null || (counter = currencyToCode.get(currencyPair.counter)) == null) {
						throw new NotAvailableFromExchangeException();
					}
					try {
						connectIfNeeded();
						Map<Long, OrderInfo> orders = coinfloor.watchOrders(base, counter, true);
						bookData.watchingOrders = true;
						for (Map.Entry<Long, OrderInfo> entry : orders.entrySet()) {
							Long id = entry.getKey();
							OrderInfo info = entry.getValue();
							bookData.orderOpened(id, makeLimitOrder(currencyPair, id, info.quantity, info.price, info.time));
						}
					}
					catch (CoinfloorException e) {
						throw new ExchangeException(e.getMessage(), e);
					}
				}
				return bookData.getOrderBook();
			}

			@Override
			public Trades getTrades(CurrencyPair currencyPair, Object... args) throws NotAvailableFromExchangeException {
				throw new NotAvailableFromExchangeException();
			}

		};
		tradeService = new TradeService() {

			@Override
			public OpenOrders getOpenOrders() throws ExchangeException, IOException {
				return getOpenOrders(createOpenOrdersParams());
			}

			@Override
			public OpenOrders getOpenOrders(OpenOrdersParams params) throws ExchangeException, IOException {
				fetchOrdersIfNeeded();
				synchronized (openOrders) {
					return new OpenOrders(new ArrayList<>(openOrders.values()));
				}
			}

			private void fetchOrdersIfNeeded() throws ExchangeException, IOException {
				if (openOrders == null) {
					try {
						connectIfNeeded();
						Map<Long, OrderInfo> orders = coinfloor.getOrders();
						LinkedHashMap<Long, LimitOrder> openOrders = new LinkedHashMap<>((orders.size() + 2) / 3 * 4);
						for (Map.Entry<Long, OrderInfo> entry : orders.entrySet()) {
							OrderInfo info = entry.getValue();
							Currency baseCurrency, counterCurrency;
							if ((baseCurrency = codeToCurrency.get(info.base)) != null && (counterCurrency = codeToCurrency.get(info.counter)) != null) {
								openOrders.put(entry.getKey(), makeLimitOrder(new CurrencyPair(baseCurrency, counterCurrency), entry.getKey(), info.quantity, info.price, info.time));
							}
						}
						CoinfloorExchange.this.openOrders = openOrders;
					}
					catch (CoinfloorException e) {
						throw new ExchangeException(e.getMessage(), e);
					}
				}
			}

			@Override
			public String placeMarketOrder(MarketOrder marketOrder) throws ExchangeException, NotAvailableFromExchangeException, IOException {
				verifyOrder(marketOrder);
				CurrencyPair currencyPair = marketOrder.getCurrencyPair();
				Integer base, counter;
				if ((base = currencyToCode.get(currencyPair.base)) == null || (counter = currencyToCode.get(currencyPair.counter)) == null) {
					throw new NotAvailableFromExchangeException();
				}
				long quantity = marketOrder.getTradableAmount().scaleByPowerOfTen(XBT_SCALE).longValueExact();
				try {
					connectIfNeeded();
					coinfloor.executeBaseMarketOrder(base, counter, marketOrder.getType() == OrderType.BID ? quantity : -quantity, 0);
				}
				catch (CoinfloorException e) {
					throw new ExchangeException(e.getMessage(), e);
				}
				return null;
			}

			@Override
			public String placeLimitOrder(LimitOrder limitOrder) throws ExchangeException, NotAvailableFromExchangeException, IOException {
				verifyOrder(limitOrder);
				CurrencyPair currencyPair = limitOrder.getCurrencyPair();
				Integer base, counter;
				if ((base = currencyToCode.get(currencyPair.base)) == null || (counter = currencyToCode.get(currencyPair.counter)) == null) {
					throw new NotAvailableFromExchangeException();
				}
				long quantity = limitOrder.getTradableAmount().scaleByPowerOfTen(XBT_SCALE).longValueExact();
				try {
					connectIfNeeded();
					return Long.toString(coinfloor.placeLimitOrder(base, counter, limitOrder.getType() == OrderType.BID ? quantity : -quantity, limitOrder.getLimitPrice().scaleByPowerOfTen(PRICE_SCALE).longValueExact(), 0, true));
				}
				catch (CoinfloorException e) {
					throw new ExchangeException(e.getMessage(), e);
				}
			}

			@Override
			public boolean cancelOrder(String orderId) throws ExchangeException, IOException {
				try {
					coinfloor.cancelOrder(Long.parseLong(orderId));
					return true;
				}
				catch (CoinfloorException e) {
					if (e.getErrorCode() == 1 /* NotFound */) {
						return false;
					}
					throw new ExchangeException(e.getMessage(), e);
				}
			}

			@Override
			public UserTrades getTradeHistory(TradeHistoryParams params) throws IOException {
				CurrencyPair currencyPair = ((TradeHistoryParamCurrencyPair) params).getCurrencyPair();
				if (currencyPair == null) {
					throw new IllegalArgumentException("currency pair must not be null");
				}
				String spec = currencyPair + "/user_transactions/";
				if (params instanceof TradeHistoryParamPaging) {
					TradeHistoryParamPaging paging = (TradeHistoryParamPaging) params;
					Integer pageLength = paging.getPageLength();
					if (pageLength != null) {
						spec += "?limit=" + pageLength;
						Integer pageNumber = paging.getPageNumber();
						if (pageNumber != null) {
							spec += "&offset=" + pageLength * pageNumber;
						}
					}
				}
				ExchangeSpecification xchgSpec = getExchangeSpecification();
				URLConnection conn = new URL((URL) xchgSpec.getExchangeSpecificParametersItem(BIST_URL_KEY), spec).openConnection();
				conn.setRequestProperty("Authorization", "Basic " + new String(Base64.encodeBase64((xchgSpec.getUserName() + '/' + xchgSpec.getApiKey() + ':' + xchgSpec.getPassword()).getBytes("UTF-8")), "US-ASCII"));
				InputStream in = conn.getInputStream();
				try {
					String baseFieldName = currencyPair.base.getCurrencyCode().toLowerCase(), counterFieldName = currencyPair.counter.getCurrencyCode().toLowerCase(), priceFieldName = baseFieldName + '_' + counterFieldName;
					ObjectMapper mapper = new ObjectMapper();
					List<?> list = mapper.readValue(new BufferedInputStream(in), new TypeReference<List<Object>>() {
					});
					ArrayList<UserTrade> trades = new ArrayList<>(list.size());
					DateFormat dateFormat = (DateFormat) dateFormatPrototype.clone();
					for (Object tradeObj : list) {
						Map<?, ?> trade = (Map<?, ?>) tradeObj;
						if (((Number) trade.get("type")).intValue() == 2) {
							OrderType type;
							BigDecimal quantity = new BigDecimal((String) trade.get(baseFieldName));
							if (quantity.signum() < 0) {
								type = OrderType.ASK;
								quantity = quantity.negate();
							}
							else {
								type = OrderType.BID;
							}
							trades.add(new UserTrade(type, quantity, currencyPair, new BigDecimal((String) trade.get(priceFieldName)), dateFormat.parse((String) trade.get("datetime")), trade.get("id").toString(), trade.get("order_id").toString(), new BigDecimal((String) trade.get("fee")), currencyPair.counter));
						}
					}
					return new UserTrades(trades, TradeSortType.SortByTimestamp);
				}
				catch (ParseException e) {
					throw new ExchangeException(e.toString(), e);
				}
				finally {
					in.close();
				}
			}

			@Override
			public TradeHistoryParams createTradeHistoryParams() {
				return new CoinfloorTradeHistoryParams();
			}

			@Override
			public OpenOrdersParams createOpenOrdersParams() {
				return null;
			}

			@Override
			public void verifyOrder(LimitOrder limitOrder) throws NotAvailableFromExchangeException, IllegalArgumentException {
				verifyOrder((Order) limitOrder);
				try {
					limitOrder.getLimitPrice().scaleByPowerOfTen(PRICE_SCALE).longValueExact();
				}
				catch (ArithmeticException e) {
					throw new IllegalArgumentException("price has too much precision", e);
				}
			}

			@Override
			public void verifyOrder(MarketOrder marketOrder) throws NotAvailableFromExchangeException, IllegalArgumentException {
				verifyOrder((Order) marketOrder);
			}

			private void verifyOrder(Order order) throws NotAvailableFromExchangeException, IllegalArgumentException {
				CurrencyPair currencyPair = order.getCurrencyPair();
				if (!currencyToCode.containsKey(currencyPair.base) || !currencyToCode.containsKey(currencyPair.counter)) {
					throw new NotAvailableFromExchangeException();
				}
				OrderType type = order.getType();
				if (type != OrderType.BID && type != OrderType.ASK) {
					throw new NotAvailableFromExchangeException();
				}
				try {
					order.getTradableAmount().scaleByPowerOfTen(XBT_SCALE).longValueExact();
				}
				catch (ArithmeticException e) {
					throw new IllegalArgumentException("amount has too much precision", e);
				}
			}

			@Override
			public Collection<Order> getOrder(String... orderIds) throws ExchangeException, IOException {
				fetchOrdersIfNeeded();
				ArrayList<Order> orders = new ArrayList<>(orderIds.length);
				synchronized (openOrders) {
					for (String orderId : orderIds) {
						LimitOrder order = openOrders.get(Long.valueOf(orderId));
						if (order != null) {
							orders.add(order);
						}
					}
				}
				return orders;
			}

		};
	}

	void connectIfNeeded() throws IOException, CoinfloorException {
		if (coinfloor == null) {
			Coinfloor coinfloor = new Coinfloor() {

				@Override
				protected void orderOpened(long id, long tonce, int base, int counter, long quantity, long price, long time, boolean own) {
					Currency baseCurrency, counterCurrency;
					if ((baseCurrency = codeToCurrency.get(base)) != null && (counterCurrency = codeToCurrency.get(counter)) != null) {
						CurrencyPair currencyPair = new CurrencyPair(baseCurrency, counterCurrency);
						Long idObj = id;
						LimitOrder order = makeLimitOrder(currencyPair, id, quantity, price, time);
						getOrCreateBookData(currencyPair).orderOpened(idObj, order);
						if (openOrders != null && own) {
							synchronized (openOrders) {
								openOrders.put(idObj, order);
							}
						}
					}
				}

				@Override
				protected void ordersMatched(long bid, long bidTonce, long ask, long askTonce, int base, int counter, long quantity, long price, long total, long bidRem, long askRem, long time, long bidBaseFee, long bidCounterFee, long askBaseFee, long askCounterFee) {
					Currency baseCurrency, counterCurrency;
					if ((baseCurrency = codeToCurrency.get(base)) != null && (counterCurrency = codeToCurrency.get(counter)) != null) {
						Long bidID = bid >= 0 ? bid : null, askID = ask >= 0 ? ask : null;
						LimitOrder bidOrder, askOrder;
						BookData bookData = getOrCreateBookData(new CurrencyPair(baseCurrency, counterCurrency));
						synchronized (bookData) {
							bidOrder = bidID == null ? null : bookData.orderChanged(bidID, bidRem);
							askOrder = askID == null ? null : bookData.orderChanged(askID, askRem);
						}
						if (openOrders != null) {
							synchronized (openOrders) {
								if (bidID != null && bidBaseFee >= 0) {
									LimitOrder order = openOrders.get(bidID);
									if (order != null) {
										openOrders.put(bidID, bidOrder == null ? makeLimitOrder(order, bidRem) : bidOrder);
									}
								}
								if (askID != null && askBaseFee >= 0) {
									LimitOrder order = openOrders.get(askID);
									if (order != null) {
										openOrders.put(askID, askOrder == null ? makeLimitOrder(order, askRem) : askOrder);
									}
								}
							}
						}
					}
				}

				@Override
				protected void orderClosed(long id, long tonce, int base, int counter, long quantity, long price, boolean own) {
					Currency baseCurrency, counterCurrency;
					if ((baseCurrency = codeToCurrency.get(base)) != null && (counterCurrency = codeToCurrency.get(counter)) != null) {
						Long idObj = id;
						getOrCreateBookData(new CurrencyPair(baseCurrency, counterCurrency)).orderClosed(idObj);
						if (openOrders != null && own) {
							synchronized (openOrders) {
								openOrders.remove(idObj);
							}
						}
					}
				}

				@Override
				protected void tickerChanged(int base, int counter, long last, long bid, long ask, long low, long high, long volume) {
					Currency baseCurrency, counterCurrency;
					if ((baseCurrency = codeToCurrency.get(base)) != null && (counterCurrency = codeToCurrency.get(counter)) != null) {
						CurrencyPair currencyPair = new CurrencyPair(baseCurrency, counterCurrency);
						getOrCreateBookData(currencyPair).cachedTicker = makeTicker(currencyPair, last, bid, ask, low, high, volume);
					}
				}

			};
			coinfloor.connect(URI.create(exchangeSpecification.getSslUri()));
			try {
				coinfloor.authenticate(Long.parseLong(exchangeSpecification.getUserName()), exchangeSpecification.getApiKey(), exchangeSpecification.getPassword());
				this.coinfloor = coinfloor;
				coinfloor = null;
			}
			finally {
				if (coinfloor != null) {
					coinfloor.disconnect();
				}
			}
		}
	}

	BookData getOrCreateBookData(CurrencyPair currencyPair) {
		synchronized (books) {
			BookData bookData = books.get(currencyPair);
			if (bookData == null) {
				books.put(currencyPair, bookData = new BookData());
			}
			return bookData;
		}
	}

	static LimitOrder makeLimitOrder(CurrencyPair currencyPair, long id, long quantity, long price, long time) {
		return new LimitOrder(quantity > 0 ? OrderType.BID : OrderType.ASK, BigDecimal.valueOf(Math.abs(quantity), XBT_SCALE), currencyPair, Long.toString(id), new Date(time / 1000), BigDecimal.valueOf(price, PRICE_SCALE), null, null, OrderStatus.NEW);
	}

	static LimitOrder makeLimitOrder(LimitOrder order, long newQuantity) {
		return new LimitOrder(order.getType(), BigDecimal.valueOf(Math.abs(newQuantity), XBT_SCALE), order.getCurrencyPair(), order.getId(), order.getTimestamp(), order.getLimitPrice(), order.getAveragePrice(), order.getCumulativeAmount(), newQuantity == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED);
	}

	static Ticker makeTicker(CurrencyPair currencyPair, long last, long bid, long ask, long low, long high, long volume) {
		Builder builder = new Ticker.Builder().currencyPair(currencyPair);
		if (last > 0) {
			builder.last(BigDecimal.valueOf(last, PRICE_SCALE));
		}
		if (bid > 0) {
			builder.bid(BigDecimal.valueOf(bid, PRICE_SCALE));
		}
		if (ask > 0) {
			builder.ask(BigDecimal.valueOf(ask, PRICE_SCALE));
		}
		if (low > 0) {
			builder.low(BigDecimal.valueOf(low, PRICE_SCALE));
		}
		if (high > 0) {
			builder.high(BigDecimal.valueOf(high, PRICE_SCALE));
		}
		return builder.volume(BigDecimal.valueOf(volume, XBT_SCALE)).build();
	}

}
