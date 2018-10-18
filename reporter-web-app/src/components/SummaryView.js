import React from 'react';
import { connect } from 'react-redux';
import PropTypes from 'prop-types';
import {
    Col, Row
} from 'antd';
import SummaryViewLicenseCharts from './SummaryViewLicenseCharts';
import SummaryViewTableErrors from './SummaryViewTableErrors';
import SummaryViewTimeline from './SummaryViewTimeline';
import { UNIQUE_COLORS } from '../data/colors/index';

const COLORS = UNIQUE_COLORS.data;

class SummaryView extends React.Component {
    constructor(props) {
        super(props);
        this.state = {};
        this.licenseColors = new Map();

        const assignColorsToLicenses = (licenses) => {
            const nrColors = COLORS.length;

            for (let i = licenses.length - 1; i >= 0; i -= 1) {
                const license = licenses[i];
                if (!this.licenseColors.has(license)) {
                    this.licenseColors.set(license, COLORS[this.licenseColors.size % nrColors]);
                }
            }
        };
        const viewData = {
            charts: {
                declaredLicenses: [],
                detectedLicenses: [],
                totalDeclaredLicenses: 0,
                totalDetectedLicenses: 0
            },
            errors: {
                open: [],
                addressed: [],
                totalOpen: 0,
                totalResolved: 0
            }
        };

        if (props.reportData) {
            this.state = {
                ...this.state,
                data: props.reportData
            };
            const { data } = this.state;

            if (data.licenses
                && data.licenses.data
                && data.licenses.total) {
                if (data.licenses.data.detected
                    && Number.isInteger(data.licenses.total.detected)) {
                    assignColorsToLicenses(Object.keys(data.licenses.data.detected));
                    viewData.charts.detectedLicenses = this.convertLicensesToChartFormat(
                        data.licenses.data.detected
                    );
                    viewData.charts.totalDetectedLicenses = data.licenses.total.detected;
                }

                if (data.licenses.data.declared
                    && Number.isInteger(data.licenses.total.declared)) {
                    assignColorsToLicenses(Object.keys(data.licenses.data.declared));
                    viewData.charts.declaredLicenses = this.convertLicensesToChartFormat(
                        data.licenses.data.declared
                    );
                    viewData.charts.totalDeclaredLicenses = data.licenses.total.declared;
                }
            }

            if (data.errors
                && data.errors.data
                && data.errors.total) {
                if (data.errors.data.open
                    && Number.isInteger(data.errors.total.open)) {
                    viewData.errors.open = data.errors.data.open;
                    viewData.errors.totalOpen = data.errors.total.open;
                }

                if (data.errors.data.addressed
                    && Number.isInteger(data.errors.total.addressed)) {
                    viewData.errors.addressed = data.errors.data.addressed;
                    viewData.errors.totalAddressed = data.errors.total.addressed;
                }
            }
        }

        this.state = {
            ...this.state,
            viewData
        };
    }

    convertLicensesToChartFormat(licenses) {
        const chartData = Object.entries(licenses).reduce((accumulator, [key, value]) => {
            accumulator[key] = {
                name: key,
                value,
                color: this.licenseColors.get(key)
            };

            return accumulator;
        }, {});

        return Object.keys(chartData).sort().reduce((accumulator, key) => {
            accumulator.push(chartData[key]);

            return accumulator;
        }, []).reverse();
    }

    render() {
        const { data, viewData } = this.state;

        return (
            <div className="ort-summary">
                <Row>
                    <Col span={22} offset={1}>
                        <SummaryViewTimeline data={{
                            ...data,
                            nrDetectedLicenses: viewData.charts.totalDetectedLicenses,
                            nrDeclaredLicenses: viewData.charts.totalDeclaredLicenses,
                            nrErrors: viewData.errors.totalOpen
                        }}
                        />
                    </Col>
                </Row>
                <Row>
                    <Col span={22} offset={1}>
                        <SummaryViewTableErrors data={viewData.errors} />
                    </Col>
                </Row>
                <Row>
                    <Col span={22} offset={1}>
                        <SummaryViewLicenseCharts data={viewData.charts} />
                    </Col>
                </Row>
            </div>
        );
    }
}

SummaryView.propTypes = {
    reportData: PropTypes.object.isRequired
};

export default connect(
    state => ({ reportData: state }),
    () => ({})
)(SummaryView);
