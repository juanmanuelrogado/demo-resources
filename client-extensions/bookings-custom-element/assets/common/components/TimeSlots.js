import React, { useEffect, useState } from 'react';
import { format } from 'date-fns';
import DatePicker from 'react-datepicker';
import 'react-datepicker/dist/react-datepicker.css';

const TimeSlots = () => {
	const siteGroupId = Liferay.ThemeDisplay.getSiteGroupId();
	const productId = document.querySelector('.cProductId h2').textContent.trim();
	const today = new Date();

	const startTime = 11;
	const endTime = 19;
	const [maxBookings, setMaxBookings] = useState(10);

	const timeSlots = [];
	const [bookings, setBookings] = useState([]);
	const [selectedDate, setSelectedDate] = useState(new Date());
	const [timeSlotCounts, setTimeSlotCounts] = useState({});
	const [userAccount, setUserAccount] = useState();
	const [sku, setSku] = useState();
	const [closedMsg, setClosedMsg] = useState();


	//
	// My User Account
	//
	const fetchUserAccount = () => {
		if (userAccount) return;

		Liferay.Util.fetch("/o/headless-admin-user/v1.0/my-user-account")
			.then((response) => response.json())
			.then((data) => {
				setUserAccount(data);
				console.log("fetchUserAccount: " + data);
			})
			.catch((error) => {
				console.error("Error fetching user account:", error);
			});
	};

	//
	// Fetch SKU
	//
	const fetchSku = () => {
		if (sku) return;

		Liferay.Util.fetch(`/o/headless-commerce-admin-catalog/v1.0/products/${productId}/skus`)
			.then((response) => response.json())
			.then((data) => {
				setSku(data.items[0]);
				console.log("fetchSKU: " + data.items[0]);
			})
			.catch((error) => {
				console.error("Error fetching sku:", error);
			});
	};


	//
	// Fetch Bookings
	//
	const fetchBookings = (productId, date) => {

		let filterProduct = `r_facilityBookings_CPDefinitionId%20eq%20%27${productId}%27`;
		let filterDate = `bookingDate%20eq%20${format(date, 'yyyy-MM-dd')}`;
console.log("fetchBookings query: " + `/o/c/bookings?pageSize=200&filter=${filterProduct}%20and%20${filterDate}`);
		Liferay.Util.fetch(`/o/c/bookings?pageSize=200&filter=${filterProduct}%20and%20${filterDate}`)
			.then((response) => response.json())
			.then((data) => {
				setBookings(data.items);
				console.log("fetchBookings: " + data.items);
			})
			.catch((error) => {
				console.error("Error fetching bookings:", error);
			});
	};


	//
	// Fetch Closed
	//
	const fetchClosingDays = (productId, date) => {
		let filterProduct = `r_facilityClosingDays_CPDefinitionId%20eq%20%27${productId}%27`;
		let filterDate = `date%20eq%20${format(date, 'yyyy-MM-dd')}`;

		Liferay.Util.fetch(`/o/c/facilitiesclosingdays?pageSize=200&filter=${filterProduct}%20and%20${filterDate}`)
			.then((response) => response.json())
			.then((data) => {
				if (data.totalCount > 0) {
					setMaxBookings(0);
					setClosedMsg(data.items[0].detailRawText);
					console.log("closed Date");
				} else {
					setMaxBookings(10);
					setClosedMsg("");
					console.log("open Date");
				}
			})
			.catch((error) => {
				console.error("Error fetching bookings:", error);
			});
	};


	//
	// Create Order
	//
	const createOrder = (date, timeSlot) => {
		console.log(`Create Order requested for Product: ${productId}, Time: ${timeSlot}, Date: ${format(date, 'yyyy-MM-dd')}`);

		Liferay.Util.fetch('/o/headless-commerce-admin-order/v1.0/orders', {
			method: 'POST',
			headers: [
				['Content-type', 'application/json'],
				['Accept', 'application/json']
			],
			body: JSON.stringify({
				accountId: userAccount.accountBriefs[0].id,
				channelId: Liferay.CommerceContext.commerceChannelId,
				createDate: today,
				currencyCode: Liferay.CommerceContext.currency.currencyCode,
				externalReferenceCode: "order-from-app-" + generateUniqueCode(),
				orderDate: today,
				orderStatus: 2,
				paymentStatus: 0,
				total: 5
			})
		})
			.then(response => response.json())
			.then(data => {
				addOrderItem(data.id);
				console.log('Order created successfully:', data);

				postBooking(date, timeSlot); // Refresh bookings
			})
			.catch(error => console.error('Booking failed:', error));
	};

	const addOrderItem = (orderId) => {
		Liferay.Util.fetch(`/o/headless-commerce-admin-order/v1.0/orders/${orderId}/orderItems`, {
			body: JSON.stringify({
				decimalQuantity: 1,
				externalReferenceCode: "item-from-app-" + generateUniqueCode(),
				finalPrice: 5,
				finalPriceWithTaxAmount: 5,
				orderId: orderId,
				quantity: 1,
				skuExternalReferenceCode: sku.externalReferenceCode,
				unitPrice: 5,
				unitPriceWithTaxAmount: 5
			}),
			method: 'POST',
			headers: [
				['Content-type', 'application/json'],
				['Accept', 'application/json']
			],
		});
	}


	//
	// Post Booking
	//
	const postBooking = async (date, timeSlot) => {
		console.log(`Booking requested for Product: ${productId}, Time: ${timeSlot}, Date: ${format(date, 'yyyy-MM-dd')}`);

		let res = await Liferay.Util.fetch('/o/c/bookings', {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({
				r_facilityBookings_CPDefinitionId: productId,
				timeSlot: timeSlot,
				bookingDate: format(date, 'yyyy-MM-dd')
			})
		})

		let response = await res.json();

		console.log('Reloading...');
		window.location.reload();

	};


	useEffect(() => {
		console.log("ProductId: " + productId);
		fetchUserAccount();
		fetchSku();
		fetchClosingDays(productId, selectedDate);
		fetchBookings(productId, selectedDate);

	}, [selectedDate]);


	useEffect(() => {
		const counts = {};
		bookings.forEach((booking) => {
			const timeSlot = booking.timeSlot;
			counts[timeSlot] = (counts[timeSlot] || 0) + 1;
		});
		setTimeSlotCounts(counts);
	}, [bookings]);


	for (let hour = startTime; hour <= endTime; hour++) {
		const time = `${hour < 10 ? '0' : ''}${hour}:00`;
		timeSlots.push(time);
	}


	const handleDateChange = (date) => {
		setSelectedDate(date);
	};


	function generateUniqueCode() {
		return '00' + today.getTime().toString(36).substring(2, 8).toUpperCase();
	}


	return (
		<div>
			<div style={{ display: 'flex', justifyContent: 'flex-start', padding: '10px', marginBottom: '10px' }}>
				<DatePicker selected={selectedDate} onChange={handleDateChange} inline />
			</div>

			<div class="text-danger" style={{ display: 'flex', justifyContent: 'flex-start', padding: '10px' }}>
				{closedMsg}
			</div>

			<div style={{ display: 'flex', overflowX: 'auto', padding: '10px' }}>
				{timeSlots.map((time) => {
					const hour = parseInt(time.split(':')[0]);
					const available = maxBookings - (timeSlotCounts[hour] || 0);
					const isFull = available <= 0;

					const handleSlotClick = () => {
						if (productId) {
							//postBooking(selectedDate, hour);
							createOrder(selectedDate, hour);
						}
					};

					return (
						<div
							key={time}
							onClick={handleSlotClick}
							style={{
								border: '1px solid #ccc',
								borderRadius: '5px',
								padding: '10px 20px',
								marginRight: '10px',
								minWidth: '100px',
								textAlign: 'center',
								boxShadow: '0 2px 4px rgba(0, 0, 0, 0.1)',
								whiteSpace: 'nowrap',
								textDecoration: isFull ? 'line-through' : 'none',
								color: isFull ? 'gray' : 'inherit',
								backgroundColor: 'white',
								cursor: isFull ? 'default' : 'pointer'
							}}
						>
							{time}
							<p>(<strong>{available}</strong> / {maxBookings})</p>
						</div>
					);
				})}
			</div>
		</div>
	);
};

export default TimeSlots;