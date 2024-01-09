package com.bestbenefits.takoyaki.service;

import com.bestbenefits.takoyaki.DTO.client.request.PartyCreationEditReqDTO;
import com.bestbenefits.takoyaki.DTO.client.response.PartyIdResDTO;
import com.bestbenefits.takoyaki.DTO.client.response.PartyInfoResDTO;
import com.bestbenefits.takoyaki.DTO.client.response.PartyListResDTO;
import com.bestbenefits.takoyaki.config.properties.party.ActivityLocation;
import com.bestbenefits.takoyaki.config.properties.party.Category;
import com.bestbenefits.takoyaki.config.properties.party.DurationUnit;
import com.bestbenefits.takoyaki.config.properties.party.PartyListTypeEnum;
import com.bestbenefits.takoyaki.config.properties.user.UserType;
import com.bestbenefits.takoyaki.config.properties.user.YakiStatus;
import com.bestbenefits.takoyaki.entity.Party;
import com.bestbenefits.takoyaki.entity.User;
import com.bestbenefits.takoyaki.entity.Yaki;
import com.bestbenefits.takoyaki.exception.party.*;
import com.bestbenefits.takoyaki.repository.BookmarkRepository;
import com.bestbenefits.takoyaki.repository.PartyRepository;
import com.bestbenefits.takoyaki.repository.YakiRepositoy;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PartyService {
    private final PartyRepository partyRepository;
    private final YakiRepositoy yakiRepository;
    private final UserService userService;
    private final BookmarkRepository bookmarkRepository;

    @Transactional
    public PartyIdResDTO createParty(Long id, PartyCreationEditReqDTO partyCreationEditReqDTO) {
        User user = userService.getUserOrThrow(id);
        Party party = partyRepository.save(partyCreationEditReqDTO.toEntity(user));

        return PartyIdResDTO.builder()
                .partyId(party.getId())
                .build();
    }

    @Transactional
    public PartyIdResDTO editParty(Long id, Long partyId, PartyCreationEditReqDTO partyCreationEditReqDTO) {
        //ID 유효성 검사
        User user = userService.getUserOrThrow(id);
        Party party = getPartyOrThrow(partyId);

        //party 상태 검사
        if (!party.isAuthor(id)) {
            throw new NotTakoException();
        }
        if (party.isDeleted()) {
            throw new NotFoundPartyException();
        }
        if (party.isClosed()) {
            throw new PartyClosedException();
        }

        Party newParty = partyCreationEditReqDTO.toEntity(user);

        //정책에 의한 파티 수정 조건검사
        if (!party.getCategory().equals(newParty.getCategory())) {
            throw new CategoryNotModifiableException(); //여기서만 사용됨
        }
        if (party.getRecruitNumber() > newParty.getRecruitNumber()) {
            throw new ModifiedRecruitNumberNotBiggerException(); //여기서만 사용됨
        }
        //TODO: 예상 마감일시와 예상 시작일시 비교 로직 정밀화 필요
        if (newParty.getPlannedClosingDate().isAfter(newParty.getPlannedClosingDate())) {
            throw new ModifiedPlannedClosingDateNotBeforeException(); //여기서만 사용됨
        }

        //영속성 컨텍스트 수정
        party.updateModifiedAt()
                .updateActivityLocation(newParty.getActivityLocation())
                .updateContactMethod(newParty.getContactMethod())
                .updateTitle(newParty.getTitle())
                .updateBody(newParty.getBody())
                .updateRecruitNumber(newParty.getRecruitNumber())
                .updatePlannedClosingDate(newParty.getPlannedClosingDate())
                .updatePlannedStartDate(newParty.getPlannedStartDate())
                .updateActivityDuration(newParty.getActivityDuration())
                .updateContact(newParty.getContact())
                .modify();

        return PartyIdResDTO.builder()
                .partyId(party.getId())
                .build();
    }

    @Transactional
    public PartyIdResDTO deleteParty(Long id, Long partyId) {
        Party p = getPartyOrThrow(partyId);

        if (!p.isAuthor(id))
            throw new NotTakoException();

        if (p.isDeleted())
            throw new NotFoundPartyException();

        if (p.isClosed())
            throw new PartyClosedException();

        p.updateModifiedAt().updateDeleteAt();
        bookmarkRepository.deleteAllByParty(p); //북마크 제거

        return PartyIdResDTO.builder()
                .partyId(p.getId())
                .build();
    }

    @Transactional
    public PartyIdResDTO closeParty(Long id, Long partyId) {
        Party p = getPartyOrThrow(partyId);

        if (!p.isAuthor(id))
            throw new NotTakoException();

        if (p.isDeleted())
            throw new NotFoundPartyException();

        if (p.isClosed())
            throw new PartyClosedException();

        p.updateModifiedAt().updateClosedAt();
        bookmarkRepository.deleteAllByParty(p); //북마크 제거

        return PartyIdResDTO.builder()
                .partyId(p.getId())
                .build();
    }

    @Transactional(readOnly = true)
    public List<PartyListResDTO> getPartiesInfoForPagination(boolean isLogin, Long id, int number, int pageNumber, Category category, ActivityLocation activityLocation){

        List<Object[]> partyList;

        User user = isLogin ? userService.getUserOrThrow(id) : null;
        partyList = partyRepository.getPartiesByFilteringAndPagination(PageRequest.of(pageNumber, number), user, category, activityLocation).getContent();

        List<PartyListResDTO> partyDTOList = new ArrayList<>();

        for (Object[] row : partyList) {
            PartyListResDTO.PartyListResDTOBuilder builder = initializePartyListBuilder(row);
            if (isLogin) builder.bookmarked((boolean) row[8]);
            partyDTOList.add(builder.build());
        }

        return partyDTOList;
    }

    @Transactional(readOnly = true)
    public List<PartyListResDTO> getPartiesInfoForLoginUser(Long id, PartyListTypeEnum partyListType){
        User user = userService.getUserOrThrow(id);

        List<Object[]> partyList;
        List<PartyListResDTO> partyDTOList = new ArrayList<>();

        //TODO: row[] 인덱스 하드코딩 개선

        switch (partyListType){
            case NOT_CLOSED_WAITING ->
                partyList = partyRepository.getNotClosedParties(user, YakiStatus.WAITING);
            case NOT_CLOSED_ACCEPTED ->
                partyList = partyRepository.getNotClosedParties(user, YakiStatus.ACCEPTED);
            case CLOSED ->
                partyList = partyRepository.getClosedParties(user);
            case WROTE ->
                partyList = partyRepository.getWroteParties(user);
            case BOOKMARKED ->
                partyList = partyRepository.getBookmarkedParties(user);
            default ->
                partyList = new ArrayList<>(); //오류 없애려고 씀, 실행될 일 절대 없음
        }
        for (Object[] row : partyList) {
            PartyListResDTO.PartyListResDTOBuilder builder = initializePartyListBuilder(row);
            if (PartyListTypeEnum.NOT_CLOSED_ACCEPTED == partyListType || PartyListTypeEnum.NOT_CLOSED_WAITING == partyListType) builder.bookmarked((boolean) row[8]);
            if (PartyListTypeEnum.WROTE == partyListType) builder.closed((boolean) row[8]);
            partyDTOList.add(builder.build());
        }

        return partyDTOList;
    }

    @Transactional(readOnly = true)
    public PartyInfoResDTO getPartyInfo(boolean isLogin, Long id, Long partyId){
        Party party = partyRepository.findById(partyId)
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(NotFoundPartyException::new);

        User user = isLogin ? userService.getUserOrThrow(id) : null;

        PartyInfoResDTO.PartyInfoResDTOBuilder builder =
                PartyInfoResDTO.builder().partyId(partyId)
                        .title(party.getTitle())
                        .nickname(party.getUser().getNickname())
                        .body(party.getBody())
                        .category(party.getCategory().getName())
                        .activityLocation(party.getActivityLocation().getName())
                        .plannedStartDate(party.getPlannedStartDate())
                        .activityDuration(DurationUnit.calculateDuration(party.getActivityDuration()))
                        .contactMethod(party.getContactMethod().getName())
                        .viewCount(party.getViewCount().intValue())
                        .closedDate(party.getClosedAt().isEqual(party.getCreatedAt()) ? null : party.getClosedAt().toLocalDate() ) //
                        .recruitNumber(party.getRecruitNumber())
                        .plannedClosingDate(party.getPlannedClosingDate());

        if (isLogin){
            UserType userType;
            if (party.getUser() == user) {
                userType = UserType.TAKO;
                builder.waitingList(yakiRepository.findWaitingList(party))
                        .acceptedList(yakiRepository.findAcceptedList(party))
                        .contact(party.getContact());
            }else{
                Yaki yaki = yakiRepository.findYakiByPartyAndUser(party, user).orElse(null);
                userType = (yaki != null) ? UserType.YAKI : UserType.OTHER;
                builder.yakiStatus(yaki.getStatus());
                if (party.getClosedAt() != null && yaki.getStatus() == YakiStatus.ACCEPTED)
                    builder.contact(party.getContact());
            }
            builder.userType(userType);
        }

        return builder.build();
    }

    @Transactional(readOnly = true)
    public Party getPartyOrThrow(Long partyId){
        return partyRepository.findById(partyId).orElseThrow(NotFoundPartyException::new);
    }

    private PartyListResDTO.PartyListResDTOBuilder initializePartyListBuilder(Object[] row) {
        int recruitNumber = (int) row[4];
        int waitingNumber = ((Long) row[6]).intValue();
        int acceptedNumber = ((Long) row[7]).intValue();
        float competitionRate = (waitingNumber != 0) ? (float) (recruitNumber - acceptedNumber)/waitingNumber : 0f;
        return PartyListResDTO.builder()
                .partyId((Long) row[0])
                .title((String) row[1])
                .category(((Category) row[2]).getName())
                .activityLocation(((ActivityLocation) row[3]).getName())
                .recruitNumber(recruitNumber)
                .plannedClosingDate((LocalDate) row[5])
                .waitingNumber(waitingNumber)
                .acceptedNumber(acceptedNumber)
                .competitionRate(competitionRate);
    }
}