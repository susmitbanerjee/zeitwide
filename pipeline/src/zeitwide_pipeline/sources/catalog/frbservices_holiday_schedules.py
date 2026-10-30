"""Federal Reserve Banks' holiday schedules: closures for Fedwire and FedACH; FedNow stays open."""

from zeitwide_pipeline.models import SourceRole
from zeitwide_pipeline.sources.base import SourceDefinition
from zeitwide_pipeline.sources.fed_holidays import FedHolidayTableParser

SOURCE = SourceDefinition(
    id="frbservices-holiday-schedules",
    publisher="Federal Reserve Banks (Federal Reserve Financial Services)",
    url="https://www.frbservices.org/about/holiday-schedules/",
    parser=FedHolidayTableParser(name="frbservices_holiday_table"),
    content_types=frozenset({"text/html"}),
    role=SourceRole.PRIMARY,
)
