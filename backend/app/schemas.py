from datetime import date, datetime
from decimal import Decimal
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class InputModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class LoginRequest(InputModel):
    account_name: str = Field(min_length=1, max_length=64)
    password: str = Field(min_length=1, max_length=128)


class DirectoryInput(InputModel):
    directory_code: str = Field(min_length=1, max_length=64)
    directory_name: str = Field(min_length=1, max_length=200)
    description: str | None = None
    leader_user_id: int
    status: Literal["DRAFT", "ENABLED", "ARCHIVED"] = "DRAFT"
    sort_no: int = 0


class DirectoryUpdate(InputModel):
    directory_name: str | None = Field(default=None, min_length=1, max_length=200)
    description: str | None = None
    leader_user_id: int | None = None
    status: Literal["DRAFT", "ENABLED", "ARCHIVED"] | None = None
    sort_no: int | None = None
    expected_version: int | None = None


class DirectoryAction(InputModel):
    expected_version: int
    confirm: bool = False
    reason: str | None = None


class PlanInput(InputModel):
    plan_code: str | None = Field(default=None, max_length=64)
    directory_id: int | None = None
    plan_name: str = Field(min_length=1, max_length=200)
    construction_scope: str = Field(min_length=1)
    start_year: int = Field(ge=2000, le=2200)
    end_year: int = Field(ge=2000, le=2200)
    school_owner_id: int | None = None

    @model_validator(mode="after")
    def valid_years(self):
        if self.end_year < self.start_year:
            raise ValueError("结束年度不得早于开始年度")
        return self


class PlanCopyInput(InputModel):
    source_plan_id: int
    plan_code: str = Field(min_length=1, max_length=64)
    plan_name: str = Field(min_length=1, max_length=200)
    directory_id: int | None = None
    construction_scope: str | None = None
    start_year: int | None = None
    end_year: int | None = None
    school_owner_id: int | None = None
    copy_mode: Literal["STRUCTURE_ONLY", "STRUCTURE_AND_TARGET"]


class PlanUpdate(InputModel):
    plan_name: str | None = None
    construction_scope: str | None = None
    start_year: int | None = None
    end_year: int | None = None
    expected_version: int


class PlanAction(InputModel):
    expected_version: int
    confirm: bool = False
    reason: str | None = None


class DirectionInput(InputModel):
    plan_id: int
    direction_code: str | None = Field(default=None, max_length=64)
    direction_name: str = Field(min_length=1, max_length=500)
    source_seq: int | None = None
    description: str | None = None
    sort_no: int = 0


class DirectionUpdate(InputModel):
    direction_name: str | None = None
    description: str | None = None
    sort_no: int | None = None
    status: Literal["ENABLED", "DISABLED"] | None = None
    expected_version: int


class DirectionAction(InputModel):
    expected_version: int
    confirm: bool = False
    reason: str | None = None


class AnnualTargetInput(InputModel):
    target_year: int
    professional_target_value: Decimal | None = None
    professional_target_bool: bool | None = None
    professional_target_text: str | None = None
    professional_target_raw: str | None = None
    school_target_value: Decimal | None = None
    school_target_bool: bool | None = None
    school_target_text: str | None = None
    school_target_raw: str | None = None
    school_division_raw: str | None = None
    confirm_status: Literal["PENDING", "CONFIRMED"] = "PENDING"
    expected_version: int | None = None


class DepartmentRelationInput(InputModel):
    org_id: int
    relation_type: Literal["LEAD", "COLLABORATOR"]
    responsibility_desc: str | None = None


class IndicatorInput(InputModel):
    direction_id: int
    indicator_code: str | None = Field(default=None, max_length=64)
    indicator_name: str = Field(min_length=1, max_length=1000)
    indicator_desc: str | None = None
    indicator_type_code: str
    unit_code: str
    target_direction: Literal["GE", "LE", "EQ"] | None = None
    calc_method: Literal["CUMULATIVE", "ANNUAL_QUOTA", "FINAL", "RATIO", "GROWTH", "BOOLEAN", "EXTERNAL"] | None = None
    statistical_rule: str | None = None
    professional_five_year_target_value: Decimal | None = None
    professional_five_year_target_bool: bool | None = None
    professional_five_year_target_text: str | None = None
    professional_five_year_target_raw: str | None = None
    school_five_year_target_value: Decimal | None = None
    school_five_year_target_bool: bool | None = None
    school_five_year_target_text: str | None = None
    school_five_year_target_raw: str | None = None
    baseline_value: Decimal | None = None
    baseline_year: int | None = None
    numerator_rule: str | None = None
    denominator_rule: str | None = None
    decimal_precision: int | None = Field(default=None, ge=0, le=8)
    external_data_source: str | None = None
    annual_targets: list[AnnualTargetInput] = []
    departments: list[DepartmentRelationInput] = []


class IndicatorUpdate(InputModel):
    direction_id: int | None = None
    indicator_name: str | None = None
    indicator_desc: str | None = None
    indicator_type_code: str | None = None
    unit_code: str | None = None
    standard_unit_code: str | None = None
    target_direction: Literal["GE", "LE", "EQ"] | None = None
    calc_method: Literal["CUMULATIVE", "ANNUAL_QUOTA", "FINAL", "RATIO", "GROWTH", "BOOLEAN", "EXTERNAL"] | None = None
    statistical_rule: str | None = None
    professional_five_year_target_value: Decimal | None = None
    professional_five_year_target_bool: bool | None = None
    professional_five_year_target_text: str | None = None
    professional_five_year_target_raw: str | None = None
    school_five_year_target_value: Decimal | None = None
    school_five_year_target_bool: bool | None = None
    school_five_year_target_text: str | None = None
    school_five_year_target_raw: str | None = None
    baseline_value: Decimal | None = None
    baseline_year: int | None = None
    numerator_rule: str | None = None
    denominator_rule: str | None = None
    decimal_precision: int | None = Field(default=None, ge=0, le=8)
    external_data_source: str | None = None
    parse_status: Literal["PENDING", "PARSED", "NEED_CONFIRM", "CONFIRMED", "FAILED"] | None = None
    annual_targets: list[AnnualTargetInput] | None = None
    departments: list[DepartmentRelationInput] | None = None
    expected_version: int


class IndicatorAction(InputModel):
    expected_version: int
    confirm: bool = False


class MilestoneTargetInput(InputModel):
    target_year: int
    target_value: Decimal | None = None
    target_bool: bool | None = None
    target_text: str | None = None
    allocation_raw: str | None = None


class AssignmentTaskInput(InputModel):
    college_org_id: int
    owner_user_id: int
    execution_scope: str | None = None
    five_year_target_value: Decimal | None = None
    five_year_target_bool: bool | None = None
    five_year_target_text: str | None = None
    annual_targets: list[MilestoneTargetInput]


class AssignmentInput(InputModel):
    tasks: list[AssignmentTaskInput] = Field(min_length=1)
    non_additive_reason: str | None = None
    expected_version: int


class TaskAction(InputModel):
    expected_version: int
    reason: str | None = None


class ObjectionInput(InputModel):
    objection_text: str = Field(min_length=1)


class ObjectionReplyInput(InputModel):
    reply_text: str = Field(min_length=1)
    objection_status: Literal["RESOLVED", "REJECTED"] = "RESOLVED"
    expected_version: int


class SubmissionInput(InputModel):
    reported_value: Decimal | None = None
    reported_bool: bool | None = None
    reported_text: str | None = None
    reported_numerator: Decimal | None = None
    reported_denominator: Decimal | None = None
    completion_desc: str = Field(min_length=1)
    achievement_name: str | None = None
    completion_date: date
    expected_version: int | None = None


class SubmissionUpdate(InputModel):
    reported_value: Decimal | None = None
    reported_bool: bool | None = None
    reported_text: str | None = None
    reported_numerator: Decimal | None = None
    reported_denominator: Decimal | None = None
    completion_desc: str | None = None
    achievement_name: str | None = None
    completion_date: date | None = None
    expected_version: int


class SubmissionAction(InputModel):
    expected_version: int
    reason: str | None = None
    confirm: bool = False


class ReviewInput(InputModel):
    review_result: Literal["APPROVED", "RETURNED"]
    review_comment: str = Field(min_length=1)
    recognized_value: Decimal | None = None
    recognized_bool: bool | None = None
    recognized_text: str | None = None
    recognized_numerator: Decimal | None = None
    recognized_denominator: Decimal | None = None
    recognition_basis: str | None = None
    confirm: bool = False
    expected_version: int


class CorrectionInput(InputModel):
    correction_reason: str = Field(min_length=1)
    expected_version: int
    review_comment: str = Field(min_length=1)
    recognized_value: Decimal | None = None
    recognized_bool: bool | None = None
    recognized_text: str | None = None
    recognized_numerator: Decimal | None = None
    recognized_denominator: Decimal | None = None
    recognition_basis: str | None = None
    confirm: bool = False


class CarryoverInput(InputModel):
    source_milestone_id: int
    target_milestone_id: int
    carryover_amount: Decimal = Field(gt=0)
    reason: str = Field(min_length=1)
    expected_version: int


class CarryoverAction(InputModel):
    action: Literal["APPROVE", "RETURN", "WITHDRAW"]
    reason: str | None = None
    confirm: bool = False
    expected_version: int


class TransferInput(InputModel):
    source_task_id: int
    target_college_org_id: int
    target_task_id: int | None = None
    target_owner_user_id: int | None = None
    transfer_year: int | None = None
    transfer_amount: Decimal = Field(gt=0)
    reason: str = Field(min_length=1)
    attachment_json: list[dict[str, Any]] | None = None
    expected_version: int


class TransferAction(InputModel):
    step_code: Literal["OUT_COLLEGE", "IN_COLLEGE", "SCHOOL_LEADER"]
    action: Literal["CONFIRM", "RETURN"]
    action_comment: str | None = None
    confirm: bool = False
    expected_version: int


class TransferWithdraw(InputModel):
    reason: str = Field(min_length=1)
    expected_version: int


class TransferCopyInput(InputModel):
    transfer_amount: Decimal | None = Field(default=None, gt=0)
    transfer_year: int | None = None
    target_college_org_id: int | None = None
    target_task_id: int | None = None
    target_owner_user_id: int | None = None
    reason: str = Field(min_length=1)
    expected_version: int


class ExternalRecognitionInput(InputModel):
    target_year: int
    recognized_value: Decimal | None = None
    recognized_bool: bool | None = None
    recognized_text: str | None = None
    external_source: str = Field(min_length=1, max_length=500)
    recognition_note: str | None = None


class ImportConfirmInput(InputModel):
    confirm: bool = True
    regenerate: bool = False
    overrides: dict[str, Any] | None = None
    auto_publish: bool = False


class ImportDuplicateInput(InputModel):
    duplicate_mode: Literal["SKIP", "RECREATE"] = "SKIP"


class UserInput(InputModel):
    account_name: str = Field(min_length=1, max_length=64)
    user_name: str = Field(min_length=1, max_length=50)
    primary_org_id: int
    password: str | None = Field(default=None, min_length=8, max_length=128)
    role_codes: list[str] = []


class UserUpdateInput(InputModel):
    user_name: str | None = Field(default=None, min_length=1, max_length=50)
    primary_org_id: int | None = None
    status: Literal["ENABLED"] | None = None
    expected_version: int


class UserDisableInput(InputModel):
    handover_user_id: int | None = None
    reason: str = Field(min_length=1)
    expected_version: int


class RoleGrantInput(InputModel):
    role_code: str
    effective_from: datetime | None = None
    effective_to: datetime | None = None


class ProjectMemberInput(InputModel):
    plan_id: int
    user_id: int
    org_id: int | None = None
    member_role: Literal["SCHOOL_OWNER", "COLLEGE_OWNER", "COLLABORATOR"]
    is_primary: bool = False
    effective_to: datetime | None = None


class HandoverInput(InputModel):
    new_user_id: int
    reason: str = Field(min_length=1)
    expected_version: int


class ConfigInput(InputModel):
    config_value: Any
    description: str | None = None
    expected_version: int | None = None
