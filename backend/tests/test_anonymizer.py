from app.services.anonymizer import contains_placeholder, mask_pii


def test_masks_phone_and_email_and_restores_them() -> None:
    text = "제 번호는 010-1234-5678이고 메일은 a.b@test.com 입니다"
    m = mask_pii(text)
    assert m.masked == "제 번호는 [PHONE_1]이고 메일은 [EMAIL_1] 입니다"
    assert m.unmask(m.masked) == text


def test_classifies_rrn_card_and_accounts_without_partial_overlaps() -> None:
    assert mask_pii("주민번호 900101-1234567").masked == "주민번호 [RRN_1]"
    assert mask_pii("카드 1234-5678-9012-3456").masked == "카드 [CARD_1]"
    assert mask_pii("국민 123-45-678901 로 보내").masked == "국민 [ACCOUNT_1] 로 보내"
    assert mask_pii("계좌 1002345678901").masked == "계좌 [ACCOUNT_1]"


def test_numbers_repeated_types_independently() -> None:
    assert mask_pii("010-1111-2222, 010-3333-4444").masked == "[PHONE_1], [PHONE_2]"


def test_leaves_text_without_pii_untouched() -> None:
    m = mask_pii("오늘 3시에 만나요")
    assert m.masked == "오늘 3시에 만나요"
    assert m.entries == []
    assert m.to_original_offset(5) == 5


def test_maps_masked_offsets_back() -> None:
    text = "010-1234-5678 몇일"
    m = mask_pii(text)
    assert m.masked == "[PHONE_1] 몇일"
    assert m.to_original_offset(m.masked.index("몇일")) == text.index("몇일")
    assert m.to_original_offset(3) == 0  # inside the placeholder


def test_detects_placeholders() -> None:
    assert contains_placeholder("[PHONE_1]로")
    assert not contains_placeholder("[메모]")
