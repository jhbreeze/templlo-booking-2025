package com.templlo.service.reservation.domain.reservation.service;

import static com.templlo.service.reservation.domain.reservation.service.model.produce.ProducerTopic.*;

import java.util.UUID;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.gson.Gson;
import com.templlo.service.reservation.domain.reservation.client.ProgramClient;
import com.templlo.service.reservation.domain.reservation.client.PromotionClient;
import com.templlo.service.reservation.domain.reservation.client.TempleClient;
import com.templlo.service.reservation.domain.reservation.client.UserClient;
import com.templlo.service.reservation.domain.reservation.client.model.request.UseCouponReq;
import com.templlo.service.reservation.domain.reservation.client.model.response.DetailProgramRes;
import com.templlo.service.reservation.domain.reservation.client.model.response.UseCouponRes;
import com.templlo.service.reservation.domain.reservation.client.model.response.wrapper.ProgramServiceWrapperRes;
import com.templlo.service.reservation.domain.reservation.controller.exception.ReservationException;
import com.templlo.service.reservation.domain.reservation.controller.exception.ReservationStatusCode;
import com.templlo.service.reservation.domain.reservation.controller.model.request.CouponUsedType;
import com.templlo.service.reservation.domain.reservation.controller.model.request.CreateReservationReq;
import com.templlo.service.reservation.domain.reservation.controller.model.response.CancelReservationRes;
import com.templlo.service.reservation.domain.reservation.controller.model.response.CreateReservationRes;
import com.templlo.service.reservation.domain.reservation.controller.model.response.RejectReservationRes;
import com.templlo.service.reservation.domain.reservation.domain.Reservation;
import com.templlo.service.reservation.domain.reservation.repository.ReservationRepository;
import com.templlo.service.reservation.domain.reservation.service.model.produce.CancelReservationProduce;
import com.templlo.service.reservation.domain.reservation.service.model.produce.CreateReservationProduce;
import com.templlo.service.reservation.domain.reservation.service.model.produce.ReservationOpenType;
import com.templlo.service.reservation.global.common.exception.BaseException;
import com.templlo.service.reservation.global.common.response.BasicStatusCode;
import com.templlo.service.reservation.global.security.UserRole;

import lombok.RequiredArgsConstructor;

// TODO : Transactional outbox 패턴 적용하고 save() 사용 안함

@Service
@Transactional(readOnly = false)
@RequiredArgsConstructor
public class ReservationCommandService {
	private final ReservationRepository reservationRepository;
	private final KafkaTemplate<String, String> kafkaTemplate;
	private final UserClient userClient;
	private final PromotionClient promotionClient;
	private final Gson gson; // TODO : KafkaTemplate 새로 만들어서 처리하기
	private final ProgramClient programClient;
	private final TempleClient templeClient;

	// TODO : 함수의 책임, 트랜잭션에 대해 생각
	public CreateReservationRes createReservation(CreateReservationReq requestDto, String userId) {
		// TODO : 사용자 id 비교 검증

		// 프로그램 조회 - 가격 화인
		DetailProgramRes detailProgramRes = getDetailProgramResponse(requestDto);

		// 쿠폰 사용 후 최종 금액 계산
		int paymentAmount = useCouponAndGetPaymentAmount(requestDto, detailProgramRes);

		// save
		Reservation reservation = requestDto.toEntity(paymentAmount);

		if (isCouponUsed(requestDto)) {
			reservation = reservation.withCouponId(requestDto.couponId());
		}
		Reservation savedReservation = reservationRepository.save(reservation);

		// 예약 신청 이벤트 발행
		produceReservationCreatedMessage(savedReservation, 0, ReservationOpenType.ADDITIONAL_OPEN);

		return CreateReservationRes.from(savedReservation);
	}

	private int useCouponAndGetPaymentAmount(CreateReservationReq requestDto, DetailProgramRes detailProgramRes) {
		if (isCouponUsed(requestDto)) {
			UseCouponRes useCouponRes = promotionClient.useCouponAndGetFinalPrice(requestDto.couponId(),
				UseCouponReq.toDto(detailProgramRes, requestDto.programDate()));
			return useCouponRes.finalPrice();
		}
		return detailProgramRes.programFee();
	}

	private boolean isCouponUsed(CreateReservationReq requestDto) {
		return requestDto.couponUsedType().equals(CouponUsedType.USED) && requestDto.couponId() != null;
	}

	private DetailProgramRes getDetailProgramResponse(CreateReservationReq requestDto) {
		ProgramServiceWrapperRes<DetailProgramRes> programResponse = programClient.getProgram(requestDto.programId());
		return programResponse.data();
	}

	public CancelReservationRes cancelReservation(UUID reservationId, String userId) {
		Reservation reservation = getReservation(reservationId);
		checkCancelUser(userId, reservation);

		reservation.updateStatusProcessingCancel();
		Reservation savedReservation = reservationRepository.save(reservation);

		produceReservationCancelMessage(reservation, 0, ReservationOpenType.ADDITIONAL_OPEN);

		return CancelReservationRes.from(savedReservation);
	}

	public RejectReservationRes rejectReservation(UUID reservationId, String userId) {
		Reservation reservation = getReservation(reservationId);

		checkRoleForTemple(reservation);

		reservation.updateStatusProcessingReject();
		Reservation savedReservation = reservationRepository.save(reservation);

		produceReservationCancelMessage(reservation, 0, ReservationOpenType.ADDITIONAL_OPEN);

		return RejectReservationRes.from(savedReservation);
	}

	private void checkRoleForTemple(Reservation reservation) {
		String role = getRole();
		if (role.equals(UserRole.TEMPLE_ADMIN.name())) {
			UUID templeId = getTempleOfProgram(reservation.getProgramId());
			checkTempleOwnership(templeId);
		}
		// MASTER 면 그냥 허용
	}

	private String getRole() {
		return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
			.map(GrantedAuthority::getAuthority)
			.findFirst()
			.orElseThrow();
	}

	private void checkTempleOwnership(UUID templeId) {
		try {
			templeClient.checkTempleOwnership(templeId);
		} catch (Exception e) {
			throw new ReservationException(ReservationStatusCode.NOT_TEMPLE_OWNER);
		}
	}

	private UUID getTempleOfProgram(UUID programId) {
		ProgramServiceWrapperRes<DetailProgramRes> programRes = programClient.getProgram(programId);
		DetailProgramRes detailProgramRes = programRes.data();
		return detailProgramRes.templeId();
	}

	private void produceReservationCreatedMessage(Reservation savedReservation, int amount,
		ReservationOpenType openType) {
		CreateReservationProduce createReservationMessage = CreateReservationProduce.from(savedReservation, amount,
			openType);
		kafkaTemplate.send(TOPIC_CREATE_RESERVATION, null, gson.toJson(createReservationMessage));
	}

	private void produceReservationCancelMessage(Reservation savedReservation, int amount,
		ReservationOpenType openType) {
		CancelReservationProduce message = CancelReservationProduce.from(savedReservation, amount, openType);
		kafkaTemplate.send(TOPIC_CANCEL_RESERVATION, null, gson.toJson(message));
	}

	private void checkCancelUser(String userId, Reservation reservation) {
		UUID userUUID = userClient.getUser(userId).data().id();

		if (!reservation.getUserId().equals(userUUID)) {
			throw new BaseException(BasicStatusCode.FORBIDDEN);
		}
	}

	private Reservation getReservation(UUID reservationId) {
		return reservationRepository.findById(reservationId)
			.orElseThrow(() -> new ReservationException(ReservationStatusCode.RESERVATION_NOT_FOUND));
	}
}
